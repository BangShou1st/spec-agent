package com.specagent.trace;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.eligibility.ActionEligibilityGate;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic safety tests for the diagnostic-only trace seam. */
class SemanticTraceInstrumentationTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(
                Files.readString(FIXTURES.resolve("agent-input-valid.json")),
                AgentRequestEnvelope.class);
    }

    @Test
    void enabledTraceCapturesStagesWithoutChangingTheInferenceRequest() throws Exception {
        AgentRequestEnvelope request = request();
        String before = AgentContracts.write(request);
        SemanticTraceRecorder recorder = SemanticTraceRecorder.forTesting(true);

        recorder.captureStateUpdateInput(request);
        AgentResponseEnvelope stateUpdate = AgentContracts.read(
                Files.readString(FIXTURES.resolve("state-update-response-valid.json")),
                AgentResponseEnvelope.class);
        recorder.captureStateUpdateOutput(stateUpdate);
        recorder.captureDecisionInput(request);
        AgentResponseEnvelope decision = AgentContracts.read(
                Files.readString(FIXTURES.resolve("decision-response-valid.json")),
                AgentResponseEnvelope.class);
        recorder.captureDecisionOutput(decision);

        assertThat(AgentContracts.write(request)).isEqualTo(before);
        SemanticTrace trace = recorder.snapshot(request.runId());
        assertThat(trace.stages()).containsKeys(
                "STATE_UPDATE_INPUT", "STATE_UPDATE_OUTPUT",
                "DECISION_INPUT", "DECISION_OUTPUT");
        assertThat(trace.stages().get("STATE_UPDATE_OUTPUT"))
                .containsKey("normalized_output");
        assertThat(trace.stages().get("STATE_UPDATE_INPUT"))
                .containsKey("semantic_fingerprint");
    }

    @Test
    void eligibilityTraceCapturesRuntimeAndValidatorEvidence() throws Exception {
        AgentRequestEnvelope base = request();
        ActionEligibilityGate gate = new ActionEligibilityGate("enforced");
        AgentRequestEnvelope enforced = gate.prepareDecisionRequest(base);
        AgentResponseEnvelope v2Decision = AgentContracts.read(
                Files.readString(FIXTURES.resolve("decision-response-valid.json")),
                AgentResponseEnvelope.class);
        AgentResponseEnvelope v3Decision = new AgentResponseEnvelope(
                "agent-decision.v3", enforced.runId(), null,
                v2Decision.observation(), v2Decision.actionProposal(), v2Decision.usage(),
                v2Decision.diagnostics(), enforced.actionEligibility().version(),
                enforced.actionEligibility().basisHash(), v2Decision.eligibilityEvidenceRefs());
        ActionEligibilityGate.Assessment assessment = gate.assess(
                enforced, v3Decision.actionProposal());
        SemanticTraceRecorder recorder = SemanticTraceRecorder.forTesting(true);

        recorder.captureActionEligibility(base.runId(), enforced, v3Decision, assessment);

        Map<String, Object> stage = recorder.snapshot(base.runId()).stages()
                .get("ACTION_ELIGIBILITY");
        assertThat(stage)
                .containsEntry("mode", "ENFORCED")
                .containsEntry("selected_eligibility_version", "action-eligibility.v1")
                .containsEntry("selected_eligibility_basis_hash",
                        enforced.actionEligibility().basisHash())
                .containsEntry("request_eligibility_basis_hash",
                        enforced.actionEligibility().basisHash())
                .containsEntry("basis_hash_match", true)
                .containsEntry("selected_family", assessment.selectedAction())
                .containsEntry("selected_family_eligible", true)
                .containsEntry("post_selection_veto_invoked", true)
                .containsEntry("post_selection_veto_result", "PASS")
                .containsEntry("post_selection_veto_reason_codes", java.util.List.of())
                .containsEntry("java_eligibility_validator_result", "PASS")
                .containsKey("action_ineligible_mapping");
    }

    @Test
    void disabledTraceIsACompleteNoOp() throws Exception {
        AgentRequestEnvelope request = request();
        SemanticTraceRecorder recorder = SemanticTraceRecorder.forTesting(false);

        recorder.captureStateUpdateInput(request);
        recorder.captureFailure(request.runId(), "STATE_UPDATE_OUTPUT",
                new IllegalStateException("not used"));

        assertThat(recorder.snapshot(request.runId()).isEmpty()).isTrue();
        assertThat(recorder.take(request.runId()).isEmpty()).isTrue();
    }

    @Test
    void semanticTraceSanitizesSecretsInValuesAndExceptionSummaries() throws Exception {
        AgentRequestEnvelope original = request();
        AgentRequestEnvelope secretRequest = new AgentRequestEnvelope(
                original.protocolVersion(), original.runId(),
                new AgentEvent(original.event().kind(), original.event().anchorNodeId(),
                        original.event().selectedOptionId(),
                        "answer=super-secret-do-not-log Authorization: Bearer abc123"),
                original.snapshot(), original.capabilities(), original.decisionBudget());
        SemanticTraceRecorder recorder = SemanticTraceRecorder.forTesting(true);
        recorder.captureStateUpdateInput(secretRequest);
        recorder.captureFailure(secretRequest.runId(), "STATE_UPDATE_OUTPUT",
                new IllegalStateException("super-secret-do-not-log"));

        String rendered = String.valueOf(recorder.snapshot(secretRequest.runId()).toMap());
        assertThat(rendered).doesNotContain("super-secret-do-not-log");
        assertThat(rendered).doesNotContain("abc123");
        assertThat(String.valueOf(SemanticTraceSanitizer.sanitize(Map.of(
                "Authorization", "Bearer another-secret"))))
                .doesNotContain("another-secret");
    }
}
