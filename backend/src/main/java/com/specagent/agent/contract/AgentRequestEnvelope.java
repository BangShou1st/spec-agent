package com.specagent.agent.contract;

import java.util.List;
import java.util.UUID;

/**
 * The full request envelope Spring sends to the Python brain for both
 * {@code POST /v1/state-updates} and {@code POST /v1/decisions}. The endpoint
 * determines the call type; the envelope itself is identical.
 */
public record AgentRequestEnvelope(String protocolVersion,
                                     UUID runId,
                                     AgentEvent event,
                                     AgentInputSnapshot snapshot,
                                     List<CapabilityDescriptor> capabilities,
                                     DecisionBudget decisionBudget) {

    public AgentRequestEnvelope {
        if (!AgentProtocol.INPUT_PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new AgentContractException(
                    "Unknown request protocol version: " + protocolVersion);
        }
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
