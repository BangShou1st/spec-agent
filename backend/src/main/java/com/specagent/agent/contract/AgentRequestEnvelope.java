package com.specagent.agent.contract;

import com.specagent.agent.eligibility.ActionEligibility;

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
                                     DecisionBudget decisionBudget,
                                     ActionEligibility actionEligibility) {

    public AgentRequestEnvelope {
        boolean v2 = AgentProtocol.INPUT_PROTOCOL_VERSION_V2.equals(protocolVersion);
        boolean v3 = AgentProtocol.INPUT_PROTOCOL_VERSION_V3.equals(protocolVersion);
        if (!v2 && !v3) {
            throw new AgentContractException(
                    "Unknown request protocol version: " + protocolVersion);
        }
        if (v2 && actionEligibility != null) {
            throw new AgentContractException("agent-input.v2 must not carry actionEligibility");
        }
        if (v3 && actionEligibility == null) {
            throw new AgentContractException("agent-input.v3 requires actionEligibility");
        }
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    public AgentRequestEnvelope(String protocolVersion,
                                UUID runId,
                                AgentEvent event,
                                AgentInputSnapshot snapshot,
                                List<CapabilityDescriptor> capabilities,
                                DecisionBudget decisionBudget) {
        this(protocolVersion, runId, event, snapshot, capabilities, decisionBudget, null);
    }
}
