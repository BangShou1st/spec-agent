package com.specagent.agent.runtime;

import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.BrainFailureCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable run-failure record must say which cause it was without changing
 * the payload shape of every failure that has no known copy.
 */
class RunFailureReasonsTest {

    private static AgentBrainUnavailableException brain(BrainFailureCode code) {
        return new AgentBrainUnavailableException("brain call failed",
                new IllegalStateException("cause"), code);
    }

    @Test
    void brainFailureCodesKeepTheirOwnReasonCode() {
        assertThat(RunFailureReasons.reasonCode(brain(BrainFailureCode.BRAIN_UNAVAILABLE)))
                .isEqualTo("brain_unavailable");
        assertThat(RunFailureReasons.reasonCode(brain(BrainFailureCode.BRAIN_TIMEOUT)))
                .isEqualTo("brain_timeout");
        assertThat(RunFailureReasons.reasonCode(brain(BrainFailureCode.MODEL_PROVIDER_FAILURE)))
                .isEqualTo("model_provider_failure");
        assertThat(RunFailureReasons.reasonCode(brain(BrainFailureCode.MODEL_CONTRACT_VIOLATION)))
                .isEqualTo("model_contract_violation");
        assertThat(RunFailureReasons.reasonCode(brain(BrainFailureCode.MODEL_UNGROUNDED_REFERENCE)))
                .isEqualTo("model_ungrounded_reference");
    }

    @Test
    void legacyTwoArgumentBrainFailureStaysTheOpaqueCode() {
        AgentBrainUnavailableException legacy = new AgentBrainUnavailableException(
                "brain call failed", new IllegalStateException("cause"));

        assertThat(legacy.failureCode()).isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
        assertThat(RunFailureReasons.reasonCode(legacy)).isEqualTo("brain_unavailable");
    }

    @Test
    void incompleteAnswerCycleIsTyped() {
        assertThat(RunFailureReasons.reasonCode(new IncompleteAnswerCycleException("no patch")))
                .isEqualTo(RunFailureReasons.ANSWER_CYCLE_INCOMPLETE);
    }

    @Test
    void domainFailuresKeepTheExceptionNameAsTheirCode() {
        assertThat(RunFailureReasons.reasonCode(new IllegalStateException("SHARED_STATE_DIVERGENCE")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void classifiedFailuresGainACodeAndReadableCopy() {
        Map<String, Object> payload =
                RunFailureReasons.payload("model_contract_violation");

        assertThat(payload).containsEntry("reason", "model_contract_violation");
        assertThat(payload).containsEntry("errorCode", "model_contract_violation");
        assertThat((String) payload.get("summary")).isNotBlank();
        assertThat((String) payload.get("summary")).doesNotContain("brain_failure", "http", "stack");
    }

    @Test
    void everyTypedBrainCodeHasUserCopy() {
        for (BrainFailureCode code : BrainFailureCode.values()) {
            Map<String, Object> payload = RunFailureReasons.payload(code.reasonCode());
            assertThat(payload)
                    .as("copy for %s", code.reasonCode())
                    .containsKeys("errorCode", "summary");
        }
    }

    @Test
    void unclassifiedFailuresKeepTheLegacySingleKeyPayload() {
        assertThat(RunFailureReasons.payload("SHARED_STATE_DIVERGENCE"))
                .isEqualTo(Map.of("reason", "SHARED_STATE_DIVERGENCE"));
    }
}
