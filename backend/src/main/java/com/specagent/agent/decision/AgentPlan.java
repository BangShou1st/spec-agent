package com.specagent.agent.decision;

import com.specagent.agent.decision.AgentAction;

/**
 * The agent's planned next step: one closed action plus the reasoning behind it.
 */
public record AgentPlan(
        AgentAction nextAction,
        String rationale
) {
    public AgentPlan {
        if (nextAction == null) {
            throw new IllegalArgumentException("nextAction is required");
        }
        rationale = rationale == null ? "" : rationale;
    }
}