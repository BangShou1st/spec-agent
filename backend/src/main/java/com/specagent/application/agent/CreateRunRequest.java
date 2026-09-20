package com.specagent.application.agent;

import com.specagent.agent.contract.AgentEvent;

import java.util.List;
import java.util.UUID;

/**
 * Request body of {@code POST /api/v1/projects/{projectId}/agent-runs}.
 *
 * <p>Extracted unchanged from the controller's nested record: the component
 * names (and therefore the JSON field names) are the wire contract. It moved to
 * the application layer because the command service — not the HTTP boundary —
 * is its consumer.
 */
public record CreateRunRequest(String operation,
                               UUID nodeId,
                               UUID sourceRouteId,
                               UUID selectedOptionId,
                               List<UUID> selectedOptionIds,
                               String freeText,
                               UUID answerId,
                               String idempotencyKey,
                               AgentEvent.PersistenceIntent persistenceIntent) {
}
