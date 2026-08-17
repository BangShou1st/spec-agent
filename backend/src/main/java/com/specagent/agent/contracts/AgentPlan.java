package com.specagent.agent.contracts;

import com.specagent.agent.AgentAction;

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