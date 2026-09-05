package com.specagent.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * The user/operator event that triggered this decision cycle. Carries the
 * operation kind and the raw inputs that belong to the event; never derived
 * global history.
 */
public record AgentEvent(String kind,
                           UUID anchorNodeId,
                           UUID selectedOptionId,
                           String freeText,
                           @JsonInclude(JsonInclude.Include.NON_NULL)
                           PersistenceIntent persistenceIntent) {

    public AgentEvent(String kind,
                      UUID anchorNodeId,
                      UUID selectedOptionId,
                      String freeText) {
        this(kind, anchorNodeId, selectedOptionId, freeText, null);
    }

    /** Runtime-owned authorization carried alongside the triggering event. */
    public enum PersistenceIntent {
        RECORD_DECISION_NODE
    }
}
