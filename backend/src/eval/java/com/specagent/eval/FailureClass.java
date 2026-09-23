package com.specagent.eval;

/**
 * Unified failure taxonomy for one evaluation attempt.
 *
 * <p>An attempt may carry several violations but always exposes one overall
 * result. Labels are typed enum values — never ad-hoc natural language
 * strings scattered across tests.
 */
public enum FailureClass {
    SCENARIO_INVALID,
    RUNTIME_INVARIANT,
    BRAIN_SCHEMA,
    FORBIDDEN_ACTION,
    REQUIRED_PROPERTY_MISSING,
    UNEXPECTED_STATE_DELTA,
    AUTHORIZATION,
    CALL_BUDGET,
    PROVIDER_FAILURE,
    CAPABILITY_FAILURE,
    JUDGE_ONLY
}
