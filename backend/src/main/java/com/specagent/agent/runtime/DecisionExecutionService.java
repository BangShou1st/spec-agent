package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunTerminalizationService;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.ActionExecutor;
import com.specagent.agent.action.ActionResult;
import com.specagent.agent.snapshot.StaleContextChecker;
import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.action.ActionEligibilityGate;
import com.specagent.agent.policy.AdvisorPolicyEngine;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.PolicyDecision;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.RunProgressRecorder;
import com.specagent.workspace.context.ContextSnapshot;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 文件名:DecisionExecutionService.java
 *
 * 用途:一次已备好的 DECISION 的共享尾部:把模型调用送过 Runtime 的
 * fail-closed 链,直到 run 进入终态。在"命令 → 持久化 → Brain → 校验 →
 * checkpoint"链路中,它对应"Brain 调用之后、校验与终态持久化"这一段,
 * 是问题起草周期与回答周期逐行共用的执行内核。
 *
 * 精确覆盖两种周期原本逐行重复的片段:DECISION 调用、响应校验、
 * eligibility 的 assess/enforce、{@code PROPOSAL_CREATED}、policy 评估
 * (含"确认后不可执行"降级)、拒绝 / 待审批分支、stale 检查、
 * {@code ActionExecutor}、产出节点持久化、完成与 {@code RUN_COMPLETED}。
 *
 * 本片段之前的一切仍归调用方:route/目标加载、活跃 route 校验、
 * input-node stale 锚点检查、上下文构建、guard、Answer 持久化、
 * STATE_UPDATE、AnswerPatch、后置状态快照重建、事件语义、预算选择——
 * 这些在每个周期各不相同,绝不在此重建。本服务只接收已备好的 Runtime
 * 对象(普通类型参数,没有巨型上下文),绝不读取 Answer/Patch 行、语义
 * planner 字段、trigger type 或 action family 来分支,也绝不触碰续跑。
 */
@Service
public class DecisionExecutionService {

    private final AgentDecisionEngine decisionEngine;
    private final AdvisorPolicyEngine policyEngine;
    private final ActionExecutor actionExecutor;
    private final AgentProposalService proposalService;
    private final AgentRunTerminalizationService terminalizationService;
    private final AgentRunEventService eventService;
    private final ExecutionFence executionFence;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final StaleContextChecker staleContextChecker;
    private final ActionEligibilityGate actionEligibilityGate;
    private final AgentTracePort semanticTraceRecorder;
    private final RunProgressRecorder progressRecorder;

    public DecisionExecutionService(AgentDecisionEngine decisionEngine,
                                    AdvisorPolicyEngine policyEngine,
                                    ActionExecutor actionExecutor,
                                    AgentProposalService proposalService,
                                    AgentRunTerminalizationService terminalizationService,
                                    AgentRunEventService eventService,
                                    ExecutionFence executionFence,
                                    org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                                    StaleContextChecker staleContextChecker,
                                    ActionEligibilityGate actionEligibilityGate,
                                    AgentTracePort semanticTraceRecorder,
                                    RunProgressRecorder progressRecorder) {
        this.decisionEngine = decisionEngine;
        this.policyEngine = policyEngine;
        this.actionExecutor = actionExecutor;
        this.proposalService = proposalService;
        this.terminalizationService = terminalizationService;
        this.eventService = eventService;
        this.executionFence = executionFence;
        this.transactionTemplate = transactionTemplate;
        this.staleContextChecker = staleContextChecker;
        this.actionEligibilityGate = actionEligibilityGate;
        this.semanticTraceRecorder = semanticTraceRecorder;
        this.progressRecorder = progressRecorder;
    }

    /**
     * 执行一次已备好的 DECISION,直到 run 进入终态。
     *
     * run/project/route 身份只来自 {@code execContext}——没有第二份副本
     * 可以漂移。envelope 与 snapshot 必须与之一致(不一致即 fail-closed),
     * 因为为某个 run 准备的输入绝不能以另一个 run 的身份执行。
     *
     * @param snapshot envelope 构建自的冻结快照
     * @param envelope 已备好的 DECISION 请求(预算与事件已由调用方设置)
     * @param execContext policy 与执行器用的执行上下文;同时也是
     *                 run/project/route 身份的唯一来源
     * @param trace 调用方持有的生命周期 trace;返回的 trace 只用调用方的
     *              分隔符追加本片段的步骤
     * @param traceSeparator 调用方在 trace 步骤之间使用的分隔符
     * @param decisionStartedPayload {@code DECISION_STARTED} 事件的 payload。
     *              answer cycle 在此记录其后置状态快照身份(修复重放锚点);
     *              question-draft cycle 记录空 payload。内容按周期不同是
     *              设计使然,归调用方负责。
     */
    public DecisionExecutionResult execute(ContextSnapshot snapshot,
                                           AgentRequestEnvelope envelope,
                                           ActionExecutionContext execContext,
                                           String trace,
                                           String traceSeparator,
                                           Map<String, Object> decisionStartedPayload) {
        UUID runId = execContext.runId();
        UUID projectId = execContext.projectId();
        UUID routeId = execContext.routeId();
        if (!Objects.equals(envelope.runId(), runId)
                || !Objects.equals(snapshot.id(), execContext.contextSnapshotId())
                || !Objects.equals(snapshot.projectId(), projectId)
                || !Objects.equals(snapshot.routeId(), routeId)) {
            throw new IllegalStateException(
                    "Prepared DECISION input does not match its execution context "
                            + "for run " + runId);
        }
        // 所有权 fencing:起草/换题/续跑的节点产物执行之前确认租约仍在。
        executionFence.assertOwnership();
        semanticTraceRecorder.captureDecisionInput(envelope);

        eventService.append(runId, AgentRunPhase.DECIDING, "DECISION_STARTED",
                decisionStartedPayload);
        AgentResponseEnvelope decision;
        try {
            decision = decisionEngine.runDecision(envelope);
            AgentBrainResponseValidator.validateDecision(envelope, decision);
            semanticTraceRecorder.captureDecisionOutput(decision);
        } catch (RuntimeException ex) {
            semanticTraceRecorder.captureFailure(runId, "DECISION_OUTPUT", ex);
            throw ex;
        }

        ActionProposal proposal = decision.actionProposal();
        ActionEligibilityGate.Assessment eligibilityAssessment =
                actionEligibilityGate.assess(envelope, proposal);
        semanticTraceRecorder.captureActionEligibility(
                runId, envelope, decision, eligibilityAssessment);
        actionEligibilityGate.enforce(eligibilityAssessment);
        eventService.append(runId, AgentRunPhase.PROPOSAL_CREATED, "PROPOSAL_CREATED", Map.of(
                "actionFamily", proposal.actionFamily(),
                "proposalId", proposal.proposalId().toString()));
        progressRecorder.note(runId, AgentRunPhase.PROPOSAL_CREATED, "已确定下一步动作");

        PolicyDecision policyDecision = policyEngine.evaluate(proposal, execContext);

        // 提案被确认后也永远无法执行的 confirmation 判定,降级为 deny——
        // 绝不持久化"能点但点了没用"的 proposal。
        if (policyDecision.requiresConfirmation()
                && !policyEngine.canProduceAcceptableProposal(proposal, execContext)) {
            policyDecision = PolicyDecision.deny(policyDecision.classification(),
                    "提案在本阶段无法在确认后执行: " + proposal.actionFamily());
        }
        semanticTraceRecorder.capturePolicyDecision(runId, policyDecision);

        if (policyDecision.denyReason() != null) {
            AgentProposal agentProposal = proposalService.createProposal(
                    proposal, runId, projectId, routeId);
            if (agentProposal.status() == ProposalStatus.PROPOSED) {
                proposalService.expireProposal(agentProposal.id());
            }
            trace = appendTrace(trace, "policy_denied:" + policyDecision.denyReason(), traceSeparator);
            terminalizationService.completeWithCheck(runId, AgentRunStatus.COMPLETED, trace);
            return new DecisionExecutionResult(runId, null, agentProposal.id(),
                    "policy_denied:" + policyDecision.denyReason(), trace);
        }

        if (policyDecision.requiresConfirmation()) {
            AgentProposal agentProposal = proposalService.createProposal(
                    proposal, runId, projectId, routeId);
            trace = appendTrace(trace, "awaiting_approval:" + agentProposal.id(), traceSeparator);
            progressRecorder.note(runId, AgentRunPhase.AWAITING_APPROVAL, "提案已提交，等待你的确认");
            terminalizationService.completeWithEvent(runId, AgentRunStatus.COMPLETED, trace,
                    AgentRunPhase.AWAITING_APPROVAL, "AWAITING_APPROVAL", Map.of(
                            "proposalId", agentProposal.id().toString()));
            return new DecisionExecutionResult(runId, null, agentProposal.id(),
                    "awaiting_approval", trace);
        }

        // 自动执行:任何变更前,proposal 的基础上下文必须仍是当前活跃快照。
        staleContextChecker.check(proposal, execContext, snapshot);
        trace = appendTrace(trace, "executing", traceSeparator);
        eventService.append(runId, AgentRunPhase.EXECUTING,
                "EXECUTING", Map.of("actionFamily", proposal.actionFamily()));
        progressRecorder.note(runId, AgentRunPhase.EXECUTING, "正在执行变更");

        // 节点产物族(CREATE_NODE / REQUEST_USER_INPUT)的执行是纯数据库
        // 变更:把图变更与带所有权条件的终态化放进同一事务——丢锁执行器
        // 的终态化落空(0 行)即整体回滚,节点绝不脱离所有权提交。
        // INVOKE_CAPABILITY 是外部副作用,继续遵守既有幂等键边界,绝不把
        // 外部调用包进数据库事务(数据库回滚不能撤销已发出的外部请求)。
        ActionFamily family = ActionFamily.fromCode(proposal.actionFamily());
        boolean graphMutation = family == ActionFamily.CREATE_NODE
                || family == ActionFamily.REQUEST_USER_INPUT;
        ActionResult execResult;
        if (graphMutation) {
            final String gateTrace = trace;
            execResult = transactionTemplate.execute(tx -> {
                // 所有权协议(第四轮):事务第一条语句取所有权行 FOR SHARE 并
                // 验证代次——所有权锁先于图/任务行锁,接管的代次递增与本
                // 事务互斥;丢锁执行器在取锁处即被整体拒绝(R4-A/R4-C)。
                executionFence.lockOwnershipForWrite();
                ActionResult result = actionExecutor.execute(proposal, execContext);
                String stepTrace = appendTrace(gateTrace, "completed", traceSeparator);
                Map<String, Object> payload = new HashMap<>();
                payload.put("actionFamily", proposal.actionFamily());
                if (result.producedNodeId() != null) {
                    payload.put("producedNodeId", result.producedNodeId().toString());
                }
                terminalizationService.completeWithResponse(runId, AgentRunStatus.COMPLETED,
                        stepTrace, result.producedNodeId(), null, payload);
                return result;
            });
            trace = appendTrace(trace, "completed", traceSeparator);
        } else {
            execResult = actionExecutor.execute(proposal, execContext);
            trace = appendTrace(trace, "completed", traceSeparator);
            Map<String, Object> payload = new HashMap<>();
            payload.put("actionFamily", proposal.actionFamily());
            if (execResult.producedNodeId() != null) {
                payload.put("producedNodeId", execResult.producedNodeId().toString());
            }
            // 只有 RESPOND_TO_USER 执行才产生用户可见的消息效果:capability
            // 调用的结果里也会带一条诊断性 message,但如果把它持久化成
            // RESPOND_MESSAGE,链路会被判定为 TERMINAL_RESPONSE 而挂起,
            // 吞掉 capability 自身的持久化事实。终态事件是终态响应的唯一
            // 事实来源——绝不另设第二个消息存储。
            String responseMessage =
                    "RESPOND_TO_USER".equals(execResult.actionFamily())
                            ? execResult.message() : null;
            terminalizationService.completeWithResponse(runId, AgentRunStatus.COMPLETED, trace,
                    execResult.producedNodeId(), responseMessage, payload);
        }

        return new DecisionExecutionResult(runId, execResult.producedNodeId(), null,
                "completed", trace);
    }

    private String appendTrace(String trace, String step, String separator) {
        if (trace == null || trace.isBlank()) {
            return step;
        }
        return trace + separator + step;
    }

    /**
     * 与 Runtime 中立的一次共享 DECISION 执行结果。只携带所有周期都需要的
     * 字段;周期特有的产物(answer/patch id)留在调用方。
     */
    public record DecisionExecutionResult(UUID runId,
                                          UUID producedNodeId,
                                          UUID proposalId,
                                          String outcome,
                                          String trace) {
    }
}
