package com.specagent.agent.contract;

import java.util.Map;
import java.util.UUID;

/**
 * The full response envelope returned by the Python brain. Exactly one of
 * {@code stateUpdate} / {@code actionProposal} is present, matching the called
 * endpoint; the runtime validates this before any persistence.
 */
public record AgentResponseEnvelope(String protocolVersion,
                                      UUID runId,
                                      StateUpdateResult stateUpdate,
                                      ObservationView observation,
                                      ActionProposal actionProposal,
                                      UsageView usage,
                                      Map<String, Object> diagnostics) {

    public AgentResponseEnvelope {
        if (!AgentProtocol.DECISION_PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new AgentContractException(
                    "Unknown response protocol version: " + protocolVersion);
        }
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
