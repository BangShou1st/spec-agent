package com.specagent.agent.policy;

/**
 * 文件名:MutationClass.java
 *
 * 用途:动作变更范围(mutation scope)的分类,供策略引擎判定该动作
 * 可以自动执行还是必须经用户确认。按风险从低到高排列:
 * 只读内部 → 可见图变更 → 已确认意图变更 → 破坏性/历史相关 → 外部副作用。
 *
 * 协作:由 AdvisorPolicyEngine 产出,随 {@link PolicyDecision} 返回。
 */
public enum MutationClass {
    READ_ONLY_INTERNAL,
    VISIBLE_GRAPH_MUTATION,
    CONFIRMED_INTENT_CHANGE,
    DESTRUCTIVE_OR_HISTORY,
    EXTERNAL_SIDE_EFFECT
}
