package com.specagent.agent.policy;

/**
 * Classification of an action's mutation scope, used by the policy engine
 * to determine whether auto-execution is permitted or user confirmation
 * is required. Ordered from least to most risky.
 */
public enum MutationClass {
    READ_ONLY_INTERNAL,
    VISIBLE_GRAPH_MUTATION,
    CONFIRMED_INTENT_CHANGE,
    DESTRUCTIVE_OR_HISTORY,
    EXTERNAL_SIDE_EFFECT
}
