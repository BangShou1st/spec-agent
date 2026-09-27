package com.specagent.agent.runtime;

import com.specagent.agent.gates.ContextGuard;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunFailureService;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.protocol.ModelContractException;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.action.ActionEligibilityGate;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 文件名:DecisionCycleService.java
 *
 * 用途:纯续跑推理周期:只做 1 次 DECISION 调用,不做 STATE_UPDATE。
 * 在"命令 → 持久化 → Brain → 校验 → checkpoint"链路中,它接管与 answer cycle
 * 无关的续跑分支(目前用于问题起草 {@code DRAFT_QUESTION}):没有新 Answer
 * 需要解释,周期从冻结上下文快照直接进入一次 DECISION,随后走与 answer cycle
 * 相同的 fail-closed 链——validator → policy → 自动执行 / 持久化 proposal +
 * AWAITING_APPROVAL / 拒绝。{@code REQUEST_USER_INPUT} proposal 会作为
 * INTERACTION 节点追加到 route tip(空 route 时追加到根节点)执行;
 * 所有 ID 与 tip 推进都由 runtime 掌控。
 *
 * fail-closed 守卫与 answer cycle 一致:run 记录的目标(入队时的 tip)
 * 在执行时必须仍是活跃 route 的 tip,且排队期间活跃 route 不得被换掉。
 */
@Service
public class DecisionCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionCycleService.class);

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentRunEventService eventService;
    private final DecisionExecutionService decisionExecution;
    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final ActionEligibilityGate actionEligibilityGate;
    private final AnswerProcessingGate answerProcessingGate;

    public DecisionCycleService(AgentRunService agentRunService,
                                AgentRunFailureService agentRunFailureService,
                                ContextBuilder contextBuilder,
                                ContextGuard contextGuard,
                                AgentInputSnapshotBuilder snapshotBuilder,
                                DecisionExecutionService decisionExecution,
                                AgentRunEventService eventService,
                                ProjectRepository projectRepository,
                                RouteRepository routeRepository,
                                ActionEligibilityGate actionEligibilityGate,
                                AnswerProcessingGate answerProcessingGate) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionExecution = decisionExecution;
        this.eventService = eventService;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.actionEligibilityGate = actionEligibilityGate;
        this.answerProcessingGate = answerProcessingGate;
    }

    /**
     * 执行一次问题起草 run:单次 DECISION,然后走共享的 policy/执行链。
     */
    public DecisionCycleResult draftQuestion(AgentRun run) {
        return draftQuestion(run, null);
    }

    /**
     * 带可选 EXPLICIT 目标 route 的问题起草。
     *
     * 当 {@code explicitRouteId != null} 时,run 在自己的 route 上起草
     * (上下文按 route 构建,guard 中跳过 Active 相等校验),这正是 route B
     * 能在 route A 处于 Active 状态时继续生成的原因。传 {@code null} 则
     * 完全保持原先的 Active-route 行为。
     */
    public DecisionCycleResult draftQuestion(AgentRun run, UUID explicitRouteId) {
        Route route = loadDraftTargetRoute(run, explicitRouteId);
        boolean explicitRoute = explicitRouteId != null;
        String trace = "created";
        try {
            // DRAFT_QUESTION 会推进 route tip。在构建/调用模型前立即复查,
            // 避免在回答"看起来已完整"时入队的 run 跨过新近暴露出的
            // 缺失 STATE_UPDATE checkpoint。
            answerProcessingGate.firstUnprocessedAnswer(route.id(), route.tipNodeId())
                    .ifPresent(pending -> {
                        throw new IncompleteAnswerCycleException(
                                "Answer " + pending.id() + " on route "
                                        + pending.routeId() + " has no processed state update",
                                pending.id(), pending.routeId(), pending.nodeId());
                    });
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = explicitRoute
                    ? contextBuilder.buildForRoute(
                            run.projectId(), route.id(), route.tipNodeId(), run.id(),
                            ContextOperationType.NORMAL)
                    : contextBuilder.buildFromActiveRoute(
                            run.projectId(), run.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), snapshot.id(), trace);
            eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            if (!contextGuard.validate(snapshot, explicitRoute).accepted()) {
                throw new ModelContractException("Context guard rejected agent run");
            }

            // 纯续跑:一次 DECISION 调用,绝不做机械的 STATE_UPDATE
            // (没有需要解释的 Answer)。
            AgentRequestEnvelope envelope = actionEligibilityGate.prepareDecisionRequest(
                    snapshotBuilder.buildEnvelope(
                            run.id(), snapshot,
                            new AgentEvent("CONTINUE", route.tipNodeId(), null, null),
                            new DecisionBudget(1)));

            ActionExecutionContext execContext = new ActionExecutionContext(
                    run.id(), run.projectId(), route.id(), snapshot.id(),
                    route.tipNodeId(), null, null);
            DecisionExecutionService.DecisionExecutionResult executed =
                    decisionExecution.execute(snapshot, envelope, execContext,
                            trace, ">", Map.of());
            return new DecisionCycleResult(run.id(), executed.producedNodeId(),
                    "awaiting_approval".equals(executed.outcome())
                            ? executed.proposalId() : null,
                    executed.outcome());
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * 加载并校验 run 的起草目标:入队时记录的 tip 必须仍是该 route 的 tip。
     * 两者可以同时为 null(空 route 上的根节点起草)。
     *
     * Active 模式(默认)下,run 所在的 route 还必须是项目的 Active route。
     * explicit 模式刻意不要求这一相等性——run 拥有自己的 route——但归属关系
     * 与 OPEN 状态仍由入队时的 {@code RunService.resolveTargetRoute} 强制,
     * 并在此处通过 route 查询重新校验。
     */
    private Route loadDraftTargetRoute(AgentRun run, UUID explicitRouteId) {
        if (explicitRouteId == null) {
            Project project = projectRepository.findById(run.projectId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Project not found: " + run.projectId()));
            if (!project.activeRouteId().equals(run.routeId())) {
                throw new IllegalStateException(
                        "Draft target route is no longer the active route: " + run.routeId());
            }
        }
        Route route = routeRepository.findById(run.routeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Route not found: " + run.routeId()));
        if (explicitRouteId == null && !Objects.equals(run.inputNodeId(), route.tipNodeId())) {
            throw new IllegalStateException(
                    "Draft target is no longer the active route tip: " + run.inputNodeId());
        }
        if (explicitRouteId != null && !Objects.equals(run.inputNodeId(), route.tipNodeId())) {
            throw new IllegalStateException(
                    "Draft target is not the tip of route " + run.routeId() + ": " + run.inputNodeId());
        }
        return route;
    }

    private void failIfNotTerminal(UUID runId, String trace, RuntimeException ex) {
        LOG.warn("Agent run {} failed at {}: {}", runId, trace, ex.getMessage());
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            String reason = RunFailureReasons.reasonCode(ex);
            agentRunFailureService.fail(runId, appendTrace(trace, "failed:" + reason), ex);
        }
    }

    private String appendTrace(String trace, String step) {
        return trace + ">" + step;
    }

    /** 一次问题起草周期结束后的结果视图。 */
    public record DecisionCycleResult(UUID runId, UUID producedNodeId,
                                      UUID proposalId, String outcome) {
    }
}
