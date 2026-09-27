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
import com.specagent.agent.action.ActionEligibilityGate;
import com.specagent.agent.gates.ContextGuard;
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
 * 文件名:ContinuationCycleService.java
 *
 * 用途:自治续跑 run 的执行器——恰好 1 次全新 DECISION,没有 STATE_UPDATE。
 *
 * 续跑子 run 把因果锚点记录在 {@code inputNodeId}(创建时父 run 的结果
 * tip)。执行在这里重新锚定:实际路线 tip 必须仍与之相等,否则子 run 在任何
 * 模型调用之前就已过期——绝不追随更新的外部因果。
 *
 * 观察始终是基于当前 runtime 事实(图、claims、回答/补丁、capability 结果)
 * 构建的全新快照,绝不重放父 run 的快照。DECISION 尾段(校验 → 资格 → 策略
 * → 执行 → 终态化)完全交给 {@link DecisionExecutionService};本服务只负责
 * 准备续跑输入。它绝不读取语义规划字段、绝不按动作族分支、也绝不决定是否
 * 继续续跑——下一个边界属于协调器的 post-commit 钩子,绝不在本 run 内。
 */
@Service
public class ContinuationCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuationCycleService.class);

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

    public ContinuationCycleService(AgentRunService agentRunService,
                                    AgentRunFailureService agentRunFailureService,
                                    ContextBuilder contextBuilder,
                                    ContextGuard contextGuard,
                                    AgentInputSnapshotBuilder snapshotBuilder,
                                    AgentRunEventService eventService,
                                    DecisionExecutionService decisionExecution,
                                    ProjectRepository projectRepository,
                                    RouteRepository routeRepository,
                                    ActionEligibilityGate actionEligibilityGate) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.eventService = eventService;
        this.decisionExecution = decisionExecution;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.actionEligibilityGate = actionEligibilityGate;
    }

    /** 一次续跑循环结束后的事后视图。 */
    public record ContinuationCycleResult(UUID runId, UUID producedNodeId,
                                          UUID proposalId, String outcome) {
    }

    /**
     * 执行一次续跑 run:重新锚定 → 全新观察 → 一次 DECISION。
     */
    public ContinuationCycleResult executeContinuation(AgentRun run) {
        Route route = loadContinuationTargetRoute(run);
        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = contextBuilder.buildForRoute(
                    run.projectId(), route.id(), route.tipNodeId(), run.id(),
                    ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), snapshot.id(), trace);
            eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            // 上面的路线/tip 重新锚定锁定了执行目标;共享守卫对全新快照做
            // 再校验(路线存在、OPEN、仍是活跃路线、hash 存在)。Active 模式
            // 的子 run 在这里拒绝活跃路线切换:零模型调用、零动作、零子 run;
            // 针对 EXPLICIT 路线创建的子 run(其链路独立于 Active 指针运行)
            // 则保留自己的路线。
            if (!contextGuard.validate(snapshot, isExplicitRouteRun(run)).accepted()) {
                throw new ModelContractException("Context guard rejected continuation run");
            }

            // 全新续跑:一次 DECISION 调用,绝不是 STATE_UPDATE。
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
                            trace, "\n", Map.of(
                                    "snapshotId", snapshot.id().toString(),
                                    "contextHash", snapshot.contextHash()));
            String outcome = executed.outcome();
            UUID proposalId = "awaiting_approval".equals(outcome)
                    ? executed.proposalId() : null;
            return new ContinuationCycleResult(run.id(), executed.producedNodeId(),
                    proposalId, outcome);
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * 加载并重新锚定续跑目标:run 的路线必须仍属于该项目,且实际 tip 必须仍
     * 等于子 run 创建时记录的 {@code inputNodeId} 锚点。tip 已移动会在这里
     * fail-closed——早于任何快照、模型调用或变更——且失败会终态化该 run,
     * 不再派生后续子 run。
     */
    private Route loadContinuationTargetRoute(AgentRun run) {
        Project project = projectRepository.findById(run.projectId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Project not found: " + run.projectId()));
        Route route = routeRepository.findById(run.routeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Route not found: " + run.routeId()));
        if (!route.projectId().equals(project.id())) {
            throw new IllegalStateException(
                    "Continuation route does not belong to project: " + run.routeId());
        }
        if (!Objects.equals(run.inputNodeId(), route.tipNodeId())) {
            throw new StaleRunTargetException(
                    "Continuation anchor moved since child " + run.id()
                            + " was created: expected " + run.inputNodeId()
                            + " but live tip is " + route.tipNodeId());
        }
        return route;
    }

    /**
     * 本 run 自身的事件载荷是否把它标记为 EXPLICIT 路线 run。
     *
     * 在创建时记录({@code RunService.createContinueRun}),适用于父链路
     * 运行在"非项目 Active 路线"上的子 run。只有这样的子 run 才允许跳过守卫
     * 的 Active 相等规则——Active 模式子 run 在 Active 指针移动时保持
     * fail-closed,与从前完全一致。
     */
    private boolean isExplicitRouteRun(AgentRun run) {
        return eventService.findByRunId(run.id()).stream()
                .filter(event -> "RUN_CREATED".equals(event.eventType()))
                .map(com.specagent.agent.runevent.AgentRunEvent::payload)
                .findFirst()
                .map(payload -> "EXPLICIT".equals(payload.get("routeSelection")))
                .orElse(false);
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
        return trace == null || trace.isBlank() ? step : trace + "\n" + step;
    }
}
