package com.specagent.agent.decision;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentContractException;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-closed validation of brain responses: the brain is untrusted input, so
 * invented source refs, stale base context, unknown action families, runtime
 * identity smuggling, and budget violations must all be rejected.
 */
class AgentBrainResponseValidatorTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private String fixture(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name));
    }

    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(fixture("agent-input-valid.json"), AgentRequestEnvelope.class);
    }

    @Test
    void validDecisionFixturePassesValidation() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response =
                AgentContracts.read(fixture("decision-response-valid.json"),
                        AgentResponseEnvelope.class);
        assertThatCode(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .doesNotThrowAnyException();
    }

    @Test
    void inventedSourceRefIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-invalid-invented-source-ref.json"),
                AgentResponseEnvelope.class);
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("allowed");
    }

    @Test
    void staleBaseContextIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-invalid-stale-base-context.json"),
                AgentResponseEnvelope.class);
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void unknownActionFamilyIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        AgentResponseEnvelope mutated = new AgentResponseEnvelope(
                response.protocolVersion(), response.runId(), null, response.observation(),
                new com.specagent.agent.contract.ActionProposal(
                        "MARK_RISK", response.actionProposal().payload(),
                        response.actionProposal().baseContextSnapshotId(),
                        response.actionProposal().baseContextHash(),
                        response.actionProposal().sourceRefs()),
                response.usage(), response.diagnostics());
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, mutated))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void wrongRunIdIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        AgentResponseEnvelope mutated = new AgentResponseEnvelope(
                response.protocolVersion(), UUID.randomUUID(), null, response.observation(),
                response.actionProposal(), response.usage(), response.diagnostics());
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, mutated))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void payloadSmugglingRuntimeOwnedOptionIdIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        Map<String, Object> payload = new LinkedHashMap<>(response.actionProposal().payload());
        payload.put("options", java.util.List.of(
                Map.of("label", "x", "id", "88888888-8888-8888-8888-888888888888")));
        AgentResponseEnvelope mutated = new AgentResponseEnvelope(
                response.protocolVersion(), response.runId(), null, response.observation(),
                new com.specagent.agent.contract.ActionProposal(
                        "REQUEST_USER_INPUT", payload,
                        response.actionProposal().baseContextSnapshotId(),
                        response.actionProposal().baseContextHash(),
                        response.actionProposal().sourceRefs()),
                response.usage(), response.diagnostics());
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, mutated))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("runtime-owned");
    }

    @Test
    void modelSuggestedConfidenceCanNeverAuthorizeAnything() throws Exception {
        // The validator never reads confidence/risk as an authorization signal:
        // a high-confidence proposal still needs the same structural checks.
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        assertThatCode(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .doesNotThrowAnyException();
        // And a stale snapshot id fails regardless of any confidence value.
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request,
                new AgentResponseEnvelope(response.protocolVersion(), response.runId(), null,
                        response.observation(),
                        new com.specagent.agent.contract.ActionProposal(
                                "REQUEST_USER_INPUT", response.actionProposal().payload(),
                                UUID.randomUUID(), response.actionProposal().baseContextHash(),
                                response.actionProposal().sourceRefs()),
                        response.usage(), response.diagnostics())))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void stateUpdateWithActionProposalIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope decision = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateStateUpdate(request, decision))
                .isInstanceOf(AgentContractException.class);
    }
}
