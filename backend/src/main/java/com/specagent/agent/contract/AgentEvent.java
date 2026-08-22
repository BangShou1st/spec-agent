package com.specagent.agent.contract;

import java.util.UUID;

/**
 * The user/operator event that triggered this decision cycle. Carries the
 * operation kind and the raw inputs that belong to the event; never derived
 * global history.
 */
public record AgentEvent(String kind,
                           UUID anchorNodeId,
                           UUID selectedOptionId,
                           String freeText) {
}
