package com.specagent.agent.contract;

import java.util.List;
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
                                      Map<String, Object> diagnostics,
                                      String selectedEligibilityVersion,
                                      String selectedEligibilityBasisHash,
                                      List<String> eligibilityEvidenceRefs) {

    public AgentResponseEnvelope {
        boolean v2 = AgentProtocol.DECISION_PROTOCOL_VERSION_V2.equals(protocolVersion);
        boolean v3 = AgentProtocol.DECISION_PROTOCOL_VERSION_V3.equals(protocolVersion);
        if (!v2 && !v3) {
            throw new AgentContractException(
                    "Unknown response protocol version: " + protocolVersion);
        }
        eligibilityEvidenceRefs = eligibilityEvidenceRefs == null
                ? List.of() : List.copyOf(eligibilityEvidenceRefs);
        boolean carriesEligibility = selectedEligibilityVersion != null
                || selectedEligibilityBasisHash != null
                || !eligibilityEvidenceRefs.isEmpty();
        if (v2 && carriesEligibility) {
            throw new AgentContractException(
                    "agent-decision.v2 must not carry eligibility selection fields");
        }
        if (v3 && actionProposal != null
                && (selectedEligibilityVersion == null
                || selectedEligibilityBasisHash == null)) {
            throw new AgentContractException(
                    "agent-decision.v3 Decision requires eligibility version and basis hash");
        }
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public AgentResponseEnvelope(String protocolVersion,
                                 UUID runId,
                                 StateUpdateResult stateUpdate,
                                 ObservationView observation,
                                 ActionProposal actionProposal,
                                 UsageView usage,
                                 Map<String, Object> diagnostics) {
        this(protocolVersion, runId, stateUpdate, observation, actionProposal, usage,
                diagnostics, null, null, List.of());
    }
}
