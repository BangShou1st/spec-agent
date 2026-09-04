package com.specagent.trace;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
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
