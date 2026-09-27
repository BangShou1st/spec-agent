package com.specagent.agent.runtime;

import com.specagent.agent.gates.ContextGuard;

import com.specagent.agent.snapshot.StaleContextChecker;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunFailureService;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.protocol.ModelContractException;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.snapshot.StaleContextChecker;
import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.RunProgressRecorder;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.route.RegenerateResult;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.route.RouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:ReplacementCycleService.java
 *
 * 用途:替换(replacement)周期:恰好 1 次 DECISION 调用产出替换问题的
 * 内容,随后由 runtime 确定性地提交拓扑。模型在这里绝不直接修改图——
 * 替换拓扑(旧 route 置为 SUPERSEDED、新建一条全新身份的 OPEN route、
 * 记录来源 route 的出处)始终由 {@code RouteService.commitReplacementFromNode}
 * 掌控,且提交前必须确认 proposal 的基础上下文仍是当前活跃快照。
 *
 * fail-closed 守卫:执行时目标节点必须仍位于源 route 的谱系中,且替换
 * 问题必须与被拒绝的问题不同。
 */
@Service
public class ReplacementCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(ReplacementCycleService.class);

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final AgentRunEventService eventService;
    private final StaleContextChecker staleContextChecker;
    private final NodeService nodeService;
    private final RouteRepository routeRepository;
    private final RouteService routeService;
    private final RunProgressRecorder progressRecorder;
    private final ExecutionFence executionFence;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public ReplacementCycleService(AgentRunService agentRunService,
                                   AgentRunFailureService agentRunFailureService,
                                   ContextBuilder contextBuilder,
                                   ContextGuard contextGuard,
                                   AgentInputSnapshotBuilder snapshotBuilder,
                                   AgentDecisionEngine decisionEngine,
                                   AgentRunEventService eventService,
                                   StaleContextChecker staleContextChecker,
                                   NodeService nodeService,
                                   RouteRepository routeRepository,
                                   RouteService routeService,
                                   RunProgressRecorder progressRecorder,
                                   ExecutionFence executionFence,
                                org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.eventService = eventService;
        this.staleContextChecker = staleContextChecker;
        this.nodeService = nodeService;
        this.routeRepository = routeRepository;
        this.routeService = routeService;
        this.progressRecorder = progressRecorder;
        this.executionFence = executionFence;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 执行一次重新生成 run:冻结替换上下文,一次 DECISION,确定性的
     * 拓扑提交。
     */
    public RegenerateResult regenerate(AgentRun run, UUID projectId,
                                       UUID sourceRouteId, UUID targetNodeId,
                                       String userInstruction) {
        Node targetNode = nodeService.getNode(targetNodeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Target node not found: " + targetNodeId));
        if (!targetNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Target node does not belong to project");
        }
        Route sourceRoute = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Route not found: " + sourceRouteId));

        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = contextBuilder.buildForReplacement(
                    projectId, sourceRouteId, targetNodeId);
            agentRunService.attachContext(run.id(), snapshot.id(), trace);
            eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            if (!contextGuard.validate(snapshot).accepted()) {
                throw new ModelContractException("Replacement context rejected");
            }

            // 用户指令随事件的 free text 传递;锚点就是被拒绝的目标节点本身。
            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    run.id(), snapshot,
                    new AgentEvent("CONTINUE", targetNodeId, null, userInstruction),
                    new DecisionBudget(1));

            trace = appendTrace(trace, "deciding");
            eventService.append(run.id(), AgentRunPhase.DECIDING, "DECISION_STARTED", Map.of());
            progressRecorder.note(run.id(), AgentRunPhase.DECIDING, "正在重新生成该节点的问题");
            AgentResponseEnvelope response = decisionEngine.runDecision(envelope);
            AgentBrainResponseValidator.validateDecision(envelope, response);
            ActionProposal proposal = response.actionProposal();
            eventService.append(run.id(), AgentRunPhase.PROPOSAL_CREATED, "PROPOSAL_CREATED",
                    Map.of("actionFamily", proposal.actionFamily(),
                           "proposalId", proposal.proposalId().toString()));

            if (ActionFamily.fromCode(proposal.actionFamily()) != ActionFamily.REQUEST_USER_INPUT) {
                agentRunService.fail(run.id(),
                        appendTrace(trace, "failed:unexpected_action"));
                throw new ModelContractException(
                        "Expected REQUEST_USER_INPUT from replacement DECISION");
            }

            Map<String, Object> payload = proposal.payload();
            String question = stringOrNull(payload.get("questionText"));
            String purpose = stringOrNull(payload.get("purpose"));
            boolean allowFreeAnswer = payload.get("allowFreeAnswer") instanceof Boolean b && b;
            boolean allowMultiSelect = payload.get("allowMultiSelect") instanceof Boolean b && b;
            List<NodeOption> options = parseOptions(payload.get("options"));

            if (question == null || question.isBlank()) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:empty_question"));
                throw new ModelContractException("Replacement question must not be blank");
            }
            if (normalize(question).equals(normalize(targetNode.question()))) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:duplicate_question"));
                throw new ModelContractException(
                        "Replacement question must differ from the rejected question");
            }

            // proposal 是基于冻结的替换快照构建的;提交任何拓扑之前,
            // 必须确认它仍是当前的活跃快照。
            ActionExecutionContext execContext = new ActionExecutionContext(
                    run.id(), projectId, sourceRouteId, snapshot.id(),
                    targetNodeId, null, userInstruction);
            staleContextChecker.check(proposal, execContext, snapshot);

            // 活跃状态回归守卫:冻结快照证明了模型当时看到的内容;这里
            // 再次检查源 route 自那之后没有移动(追加了新 tip、route 被删除、
            // 目标被移除)。route 生命周期有效性由 commit 内部的
            // requireExplorationSource 重新校验;这里只钉死 tip 身份与谱系。
            // 基于过期 route 状态做出的决策绝不允许提交。
            staleContextChecker.verifyLiveExecutionPreconditions(
                    sourceRouteId, sourceRoute.tipNodeId(),
                    targetNodeId);

            trace = appendTrace(trace, "committing_replacement");
            eventService.append(run.id(), AgentRunPhase.EXECUTING, "EXECUTING",
                    Map.of("actionFamily", "COMMIT_REPLACEMENT"));

            // 把冻结的期望 tip 传入提交边界,使同一个 stale-tip 检查在项目锁
            // 之下、提交事务内部再执行一次(本服务的读取与拓扑变更之间
            // 不留 TOCTOU 窗口)。
            // 拓扑提交与带所有权条件的检查点/终态写入在同一事务(原子协议):
            // 丢锁执行器的检查点落空(0 行)即整体回滚,替换节点绝不脱离
            // 所有权提交到图上。
            final UUID commitTargetNodeId = targetNodeId;
            final String commitQuestion = question;
            final String commitPurpose = purpose;
            final List<NodeOption> commitOptions = options;
            final boolean commitAllowFree = allowFreeAnswer;
            final boolean commitAllowMulti = allowMultiSelect;
            final String baseTrace = trace;
            RegenerateResult result = transactionTemplate.execute(tx -> {
                // 所有权协议(第四轮):事务第一条语句取所有权行 FOR SHARE 并
                // 验证代次——所有权锁先于拓扑/项目行锁,接管的代次递增与本
                // 事务互斥;丢锁执行器在取锁处即被整体拒绝(R4-A/R4-C)。
                executionFence.lockOwnershipForWrite();
                RegenerateResult committed = routeService.commitReplacementFromNode(
                        projectId, sourceRouteId, commitTargetNodeId, sourceRoute.tipNodeId(),
                        null, commitQuestion, commitPurpose, commitOptions,
                        commitAllowFree, commitAllowMulti);
                contextBuilder.buildForRegenerate(projectId, sourceRouteId, commitTargetNodeId,
                        committed.replacementRoute().id(), committed.replacementNode().id(),
                        userInstruction);
                String stepTrace = appendTrace(baseTrace, "persisted_node");
                agentRunService.markPersistedNode(run.id(), committed.replacementNode().id(), stepTrace);
                stepTrace = appendTrace(stepTrace, "completed");
                agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, stepTrace);
                eventService.append(run.id(), AgentRunPhase.COMPLETED, "RUN_COMPLETED", Map.of(
                        "producedNodeId", committed.replacementNode().id().toString()));
                return committed;
            });
            trace = appendTrace(trace, "completed");

            return result;
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private List<NodeOption> parseOptions(Object optionsObj) {
        if (!(optionsObj instanceof List<?> optionList)) {
            return List.of();
        }
        List<NodeOption> options = new ArrayList<>();
        for (Object item : optionList) {
            if (item instanceof Map<?, ?> map && map.get("label") instanceof String label) {
                boolean recommended = map.get("recommended") instanceof Boolean b && b;
                options.add(new NodeOption(java.util.UUID.randomUUID(), label,
                        stringOrNull(map.get("impact")), recommended));
            }
        }
        return options;
    }

    private String stringOrNull(Object value) {
        return value instanceof String s ? s : null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
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
}
