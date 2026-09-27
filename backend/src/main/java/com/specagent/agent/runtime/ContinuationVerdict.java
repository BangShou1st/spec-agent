package com.specagent.agent.runtime;

/**
 * 文件名:ContinuationVerdict.java
 *
 * 用途:续跑协调器(continuation coordinator)视角下,一个已结束 AgentRun
 * 的中立 Runtime 判定结果。
 *
 * 每个枚举值只描述"Runtime 已经持久化做了什么",绝不描述"agent 接下来该做什么"。
 * 每个判定都必须能指名其证据(下列持久化行或事件);无法指名证据的判定不允许新增。
 * 任何判定都不读取模型生成的文本(观察、声称、冲突、目标、规划标记)。
 */
public enum ContinuationVerdict {

    /**
     * 证据:{@code agent_runs.produced_node_id} 非空,或存在该 run 的
     * {@code capability_invocations} 行且状态为已完成({@code SUCCEEDED},
     * 或 {@code FAILED}——失败也作为证据持久化,并进入后续快照)。
     * 下一个快照可以消费新事实。产出的 spec snapshot 永不计入:没有新的
     * snapshot 投影会读取它们。产出的回答和补丁也永不计入:answer cycle
     * 在自己的 DECISION 调用之前就已持久化它们,因此已经被看过。
     */
    EXECUTED_NEW_OBSERVATION,

    /**
     * 证据:本 run 的 {@code produced_node_id} 指向一个已存在的
     * {@code INTERACTION} 节点。该提问属于外部边界:无论之后是否收到回答,
     * 本链路都在此永久终止。用户后续的回答会开启一条新的
     * {@code ANSWER_CYCLE} 链——绝不会重新激活本 run。
     */
    PARKED_USER_INPUT,

    /**
     * 证据:存在该 run 的 {@code agent_proposals} 行,且状态仍为
     * {@code PROPOSED}。已决策的行({@code ACCEPTED}、{@code REJECTED}、
     * {@code EXPIRED}、{@code MODIFIED})不会挂起:run 会改由当前持久化
     * 状态重新判定。
     */
    PARKED_APPROVAL,

    /**
     * 证据:存在该 run 的 {@code RESPOND_MESSAGE} 事件行。本轮只产出了
     * 回答、没有其他持久化产物;v1 在此结束链路。
     */
    TERMINAL_RESPONSE,

    /**
     * 证据:存在该 run 的 {@code EXPIRED} proposal 行且无其他持久化效果
     * (策略拒绝分支是"先创建再过期"),或存在
     * {@code POLICY_DENIED} / {@code MUTATION_NOT_CONFIRMABLE} 事件行。
     * 什么都没改变,下一轮面对的将是完全相同的事实。
     */
    DENIED,

    /**
     * 证据:{@code agent_runs} 行状态为 {@code FAILED}。
     */
    FAILED,

    /**
     * 证据:以上任何行或事件对本 run 均不存在。本轮已终结,但产生不出
     * Runtime 能指名的持久化效果。
     */
    NO_EFFECT,

    /**
     * 证据:持久化的 {@code cycle_index}(null 按 0 读)与配置的
     * {@code spec.agent.loop.max-cycles} 满足
     * {@code cycleIndex + 1 >= maxCycles}。链路正常结束——
     * 预算耗尽只是轮数上限,不是失败。
     */
    BUDGET_EXHAUSTED,

    /**
     * 证据:已存在 {@code parent_run_id} 等于本 run 的 {@code agent_runs} 行。
     * 该终止边界已经被续跑过一次;再造一个子 run 会让链路分叉。
     */
    ALREADY_CONTINUED;

    public String code() {
        return name();
    }
}
