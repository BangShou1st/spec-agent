package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunRepository;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunEventTypes;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.StaleRunTargetException;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityInvocationRecord;
import com.specagent.capability.CapabilityResult;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:ContinuationCoordinator.java
 *
 * 用途:判断一个终态 AgentRun 是否可以派生自治续跑子 run(Slice 2+)。
 * 这里只回答"能否合法开启下一个循环",绝不回答"下一个循环该做什么"。
 *
 * 入参只有 run id;所有事实都从持久化状态重新读取,因此进程重启后
 * {@code evaluate(runId)} 会得到相同结论。任何参数都不携带模型生成的文本、
 * 策略对象或预计算结论。链路身份是惰性的:-null 的
 * {@code rootRunId}/{@code cycleIndex} 视为"自身即链根、第 0 轮",外部创建
 * 路径因此无需携带 loop 元数据。
 *
 * 子 run 创建(Slice 2)复用既有的 {@code inputNodeId} 机制作为过期锚点
 * (不加新列):子 run 记录由行推导出的期望 tip,执行时若实际路线 tip 已不再
 * 相等则 fail-closed,而不是追随更新的外部因果链。
 */
@Component
public class ContinuationCoordinator {

    private final AgentRunService agentRunService;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunEventService eventService;
    private final AgentProposalService proposalService;
    private final CapabilityInvocationRepository invocationRepository;
    private final NodeRepository nodeRepository;
    private final LoopProperties loopProperties;
    private final RunService runService;

    public ContinuationCoordinator(AgentRunService agentRunService,
                                   AgentRunRepository agentRunRepository,
                                   AgentRunEventService eventService,
                                   AgentProposalService proposalService,
                                   CapabilityInvocationRepository invocationRepository,
                                   NodeRepository nodeRepository,
                                   LoopProperties loopProperties,
                                   RunService runService) {
        this.agentRunService = agentRunService;
        this.agentRunRepository = agentRunRepository;
        this.eventService = eventService;
        this.proposalService = proposalService;
        this.invocationRepository = invocationRepository;
        this.nodeRepository = nodeRepository;
        this.loopProperties = loopProperties;
        this.runService = runService;
    }

    /**
     * 当且仅当 run 满足条件时创建续跑子 run 并返回。对同一个父 run 的重复调用
     * 会返回已落库的子 run,而不是创建第二行:确定性的 {@code continue:<parentRunId>}
     * key 加上 project 范围的幂等唯一索引裁决并发创建者,因此这里不存在
     * Java 层的 check-then-insert。
     *
     * 子 run 保持 {@code CREATED} 状态:领取与执行属于 worker(Slice 3+),
     * 绝不属于本调用。
     *
     * 锚点已过期(父 run 完成后,实际 tip 已越过行推导出的期望 tip)时拒绝
     * 创建并返回空:链路停靠,不追随外部因果。
     */
    public Optional<AgentRun> continueIfEligible(UUID runId) {
        ContinuationDecision decision = evaluate(runId);
        if (decision.verdict() == ContinuationVerdict.ALREADY_CONTINUED) {
            return agentRunRepository.findChildByParentRunId(runId);
        }
        if (!decision.eligible()) {
            return Optional.empty();
        }
        AgentRun parent = agentRunService.getRun(runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent run not found: " + runId));
        try {
            return Optional.of(runService.createContinueRun(parent).run());
        } catch (StaleRunTargetException stale) {
            return Optional.empty();
        }
    }

    /**
     * 从持久化状态裁决单个 run。终态 {@code FAILED} 映射到 {@code FAILED};
     * 非终态 run 属于调用方错误,直接抛异常。
     */
    public ContinuationDecision evaluate(UUID runId) {
        AgentRun run = agentRunService.getRun(runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent run not found: " + runId));
        if (run.status() == AgentRunStatus.FAILED) {
            return ContinuationDecision.of(runId, ContinuationVerdict.FAILED,
                    "run terminally failed");
        }
        if (run.status() != AgentRunStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Continuation needs a terminal run: " + runId);
        }
        if (agentRunRepository.findChildByParentRunId(runId).isPresent()) {
            return ContinuationDecision.of(runId, ContinuationVerdict.ALREADY_CONTINUED,
                    "a continuation child already exists");
        }
        int effectiveCycle = run.cycleIndex() != null ? run.cycleIndex() : 0;
        if (effectiveCycle + 1 >= loopProperties.getMaxCycles()) {
            return ContinuationDecision.of(runId, ContinuationVerdict.BUDGET_EXHAUSTED,
                    "cycle " + effectiveCycle + " reaches max-cycles "
                            + loopProperties.getMaxCycles());
        }
        Optional<AgentProposal> proposal = proposalService.findByRunId(runId);
        if (proposal.isPresent() && proposal.get().status() == ProposalStatus.PROPOSED) {
            return ContinuationDecision.of(runId, ContinuationVerdict.PARKED_APPROVAL,
                    "proposal awaits decision: " + proposal.get().id());
        }
        if (producedExternalBoundary(run)) {
            return ContinuationDecision.of(runId, ContinuationVerdict.PARKED_USER_INPUT,
                    "produced question node ends this chain: " + run.producedNodeId());
        }
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        if (events.stream().anyMatch(ContinuationCoordinator::isRespondMessage)) {
            return ContinuationDecision.of(runId, ContinuationVerdict.TERMINAL_RESPONSE,
                    "run emitted a response message");
        }
        if (hasNewFacts(run)) {
            return ContinuationDecision.of(runId,
                    ContinuationVerdict.EXECUTED_NEW_OBSERVATION, "durable facts persisted");
        }
        if (isDenied(proposal, events)) {
            return ContinuationDecision.of(runId, ContinuationVerdict.DENIED,
                    "policy denied without durable change");
        }
        return ContinuationDecision.of(runId, ContinuationVerdict.NO_EFFECT,
                "no consumable durable effect");
    }

    /**
     * 本 run 是否产出了交互(interaction)节点。产出的"问题"是一个外部边界:
     * 无论之后是否收到回答,这条链到此永久结束。之后的用户回答会开启一条新的
     * ANSWER_CYCLE 链——绝不会重新激活本 run。结论只从节点行推导——绝不依据
     * 请求的动作族名称,也绝不依据回答状态。
     */
    private boolean producedExternalBoundary(AgentRun run) {
        if (run.producedNodeId() == null) {
            return false;
        }
        Optional<Node> node = nodeRepository.findById(run.producedNodeId());
        return node.isPresent() && node.get().kind() == NodeKind.INTERACTION;
    }

    private static boolean isRespondMessage(AgentRunEvent event) {
        return AgentRunEventTypes.RESPOND_MESSAGE_EVENT.equals(event.eventType());
    }

    /**
     * 判断下一个快照能否消费本 run 留下的东西:产出的图节点,或已完成的
     * capability invocation 行(成功与持久化失败都算——失败也会作为证据
     * 落库)。未完成的 invocation 不算。
     *
     * 产出的规格快照绝不算数:没有任何新的 {@code AgentInputSnapshot}
     * 投影会读取它,子 run 的 DECISION 观察不到它——它不是下一轮的新观察。
     * 产出的回答与补丁也绝不算数:回答循环在其 DECISION 调用之前就已持久化
     * 它们,本 run 自己的模型已经见过。
     */
    private boolean hasNewFacts(AgentRun run) {
        if (run.producedNodeId() != null) {
            return true;
        }
        return invocationRepository.findByRunId(run.id()).stream()
                .map(CapabilityInvocationRecord::status)
                .anyMatch(status -> status == CapabilityResult.Status.SUCCEEDED
                        || status == CapabilityResult.Status.FAILED);
    }

    /**
     * 本 run 是否属于"被拒绝且无效果":deny 分支遗留的过期提案,
     * 或 node-query 的 POLICY_DENIED / MUTATION_NOT_CONFIRMABLE 事件。
     */
    private static boolean isDenied(Optional<AgentProposal> proposal,
                                    List<AgentRunEvent> events) {
        if (proposal.isPresent() && proposal.get().status() == ProposalStatus.EXPIRED) {
            return true;
        }
        return events.stream().anyMatch(event ->
                AgentRunEventTypes.POLICY_DENIED_EVENT.equals(event.eventType())
                        || AgentRunEventTypes.MUTATION_NOT_CONFIRMABLE_EVENT.equals(event.eventType()));
    }
}
