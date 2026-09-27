package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunFailureService;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunTerminalizationService;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.ActionExecutor;
import com.specagent.agent.action.ActionResult;
import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.policy.AdvisorPolicyEngine;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.PolicyDecision;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunEventTypes;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 文件名:NodeQueryService.java
 *
 * 用途:针对任意节点的情境化 AI 查询:恰好一次 DECISION 调用。
 *
 * 这是图工作区模型中"问 AI 这个节点"的路径。上下文由锚点节点的谱系
 * 加显式指定的只读 route 构成;回答必须以 {@code RESPOND_TO_USER}(或
 * {@code WAIT})返回,绝不修改图。如果模型提出的是变更动作,它会被持久化为
 * 等待用户确认的 Advisor proposal——查询本身没有副作用。
 * 在"命令 → 持久化 → Brain → 校验 → checkpoint"链路中,它是 NODE_QUERY
 * 触发类型的执行服务。
 */
@Service
public class NodeQueryService {

    /**
     * @deprecated 请改读 {@link AgentRunEventTypes}。保留仅为让既有调用方
     * 继续编译;值直接转发到共享的 run 事件协议,必须与其保持一致。
     */
    @Deprecated(forRemoval = true)
    public static final String RESPOND_MESSAGE_EVENT = AgentRunEventTypes.RESPOND_MESSAGE_EVENT;
    /** @deprecated 请改读 {@link AgentRunEventTypes#POLICY_DENIED_EVENT}。 */
    @Deprecated(forRemoval = true)
    public static final String POLICY_DENIED_EVENT = AgentRunEventTypes.POLICY_DENIED_EVENT;
    /** @deprecated 请改读 {@link AgentRunEventTypes#MUTATION_NOT_CONFIRMABLE_EVENT}。 */
    @Deprecated(forRemoval = true)
    public static final String MUTATION_NOT_CONFIRMABLE_EVENT =
            AgentRunEventTypes.MUTATION_NOT_CONFIRMABLE_EVENT;

    private static final Logger LOG = LoggerFactory.getLogger(NodeQueryService.class);

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final AdvisorPolicyEngine policyEngine;
    private final ActionExecutor actionExecutor;
    private final AgentProposalService proposalService;
    private final AgentRunEventService eventService;
    private final AgentRunTerminalizationService terminalizationService;

    public NodeQueryService(AgentRunService agentRunService,
                            AgentRunFailureService agentRunFailureService,
                            ContextBuilder contextBuilder,
                            AgentInputSnapshotBuilder snapshotBuilder,
                            AgentDecisionEngine decisionEngine,
                            AdvisorPolicyEngine policyEngine,
                            ActionExecutor actionExecutor,
                            AgentProposalService proposalService,
                            AgentRunEventService eventService,
                            AgentRunTerminalizationService terminalizationService) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.policyEngine = policyEngine;
        this.actionExecutor = actionExecutor;
        this.proposalService = proposalService;
        this.eventService = eventService;
        this.terminalizationService = terminalizationService;
    }

    public record NodeQueryResult(UUID runId, String status, String message, UUID proposalId) {
    }

    /**
     * 执行节点查询:快照 → 1 次 DECISION 调用 → policy。
     */
    public NodeQueryResult executeNodeQuery(AgentRun run, UUID routeId,
                                            UUID anchorNodeId, String question) {
        UUID runId = run.id();
        String trace = "created";
        try {
            ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                    run.projectId(), routeId, anchorNodeId, question);
            agentRunService.attachContext(runId, snapshot.id(), trace);
            eventService.append(runId, AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    runId, snapshot,
                    new AgentEvent("NODE_QUERY", anchorNodeId, null, question),
                    new DecisionBudget(1));

            eventService.append(runId, AgentRunPhase.DECIDING, "DECISION_STARTED", Map.of());
            AgentResponseEnvelope decision = decisionEngine.runDecision(envelope);
            AgentBrainResponseValidator.validateDecision(envelope, decision);

            ActionProposal proposal = decision.actionProposal();
            eventService.append(runId, AgentRunPhase.PROPOSAL_CREATED, "PROPOSAL_CREATED", Map.of(
                    "actionFamily", proposal.actionFamily(),
                    "proposalId", proposal.proposalId().toString()));

            PolicyDecision policyDecision = policyEngine.evaluate(proposal, new ActionExecutionContext(
                    runId, run.projectId(), routeId, snapshot.id(), anchorNodeId, null, question));
            if (policyDecision.denyReason() != null) {
                trace = trace + "\npolicy_denied:" + policyDecision.denyReason();
                // 可持久化的终态证据:结果视图从这条事件推导 POLICY_DENIED,
                // 绝不从 trace 字符串推导。COMPLETED 状态迁移与语义事件
                // 原子提交,因此轮询永远不会观察到"COMPLETED 但
                // POLICY_DENIED 事件缺失"的中间态。
                terminalizationService.completeWithEvent(runId, AgentRunStatus.COMPLETED, trace,
                        AgentRunPhase.COMPLETED, POLICY_DENIED_EVENT,
                        Map.of("denyReason", policyDecision.denyReason(),
                                "actionFamily", proposal.actionFamily()));
                return new NodeQueryResult(runId, "policy_denied", null, null);
            }

            // 查询绝不修改图:只读 family 直接执行,所有可确认的变更
            // family 都降级为 pending proposal。确认后也永远无法执行的
            // family(没有命令路径、锚点非 tip、端点已失效)按
            // not_confirmable 报告,而不是生成"能点但无法执行"的 proposal。
            boolean readOnly = switch (ActionFamily.fromCode(proposal.actionFamily())) {
                case RESPOND_TO_USER, WAIT -> true;
                case CREATE_NODE, UPDATE_NODE, CONNECT_NODE, CREATE_ROUTE,
                     REQUEST_USER_INPUT, INVOKE_CAPABILITY, GENERATE_ARTIFACT -> false;
            };
            if (!readOnly) {
                ActionExecutionContext downgradeContext = new ActionExecutionContext(
                        runId, run.projectId(), routeId, snapshot.id(), anchorNodeId, null, question);
                if (!policyEngine.canProduceAcceptableProposal(proposal, downgradeContext)) {
                    trace = trace + "\nnot_confirmable:" + proposal.actionFamily();
                    // 与拒绝路径相同的原子终态化:COMPLETED 与
                    // MUTATION_NOT_CONFIRMABLE 一起提交,轮询永远不会短暂地
                    // 观察到"COMPLETED 但语义事件缺失"。
                    terminalizationService.completeWithEvent(runId, AgentRunStatus.COMPLETED, trace,
                            AgentRunPhase.COMPLETED, MUTATION_NOT_CONFIRMABLE_EVENT,
                            Map.of("actionFamily", proposal.actionFamily()));
                    return new NodeQueryResult(runId, "not_confirmable", null, null);
                }
                AgentProposal agentProposal = proposalService.createProposal(
                        proposal, runId, run.projectId(), routeId);
                terminalizationService.completeWithEvent(runId, AgentRunStatus.COMPLETED,
                        trace + "\nawaiting_approval:" + agentProposal.id(),
                        AgentRunPhase.AWAITING_APPROVAL, "AWAITING_APPROVAL", Map.of(
                                "proposalId", agentProposal.id().toString(),
                                "actionFamily", proposal.actionFamily()));
                return new NodeQueryResult(runId, "awaiting_approval", null, agentProposal.id());
            }

            eventService.append(runId, AgentRunPhase.EXECUTING, "EXECUTING",
                    Map.of("actionFamily", proposal.actionFamily()));
            ActionResult result = actionExecutor.execute(proposal, new ActionExecutionContext(
                    runId, run.projectId(), routeId, snapshot.id(), anchorNodeId, null, question));

            terminalizationService.completeWithResponse(runId, AgentRunStatus.COMPLETED,
                    trace + "\ncompleted", null, result.message(), Map.of());
            return new NodeQueryResult(runId, "completed", result.message(), null);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    private void failIfNotTerminal(UUID runId, RuntimeException ex) {
        String reason = RunFailureReasons.reasonCode(ex);
        LOG.warn("Node query run {} failed: {}", runId, reason);
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            agentRunFailureService.fail(runId, "failed:" + reason, ex);
        }
    }
}
