package com.specagent.globalassistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceMessage;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.model.inference.ModelOutputContract;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Structural repair unit tests: exactly one repair inference per rejected
 * semantic decision, same contract, observable repair call type.
 */
class GlobalAssistantBrainRepairTest {

    private static final String VALID_FINAL = """
            {"kind":"FINAL","assistantText":"All set."}
            """;

    private GlobalAssistantBrain brainWithScripts(Queue<String> scripts,
            AtomicReference<ModelInferenceRequest> first, AtomicReference<ModelInferenceRequest> second,
            AtomicInteger calls) {
        GlobalAssistantPromptRenderer renderer = mock(GlobalAssistantPromptRenderer.class);
        when(renderer.render(any(), any())).thenReturn(List.of(
                new ModelInferenceMessage("system", "system"),
                new ModelInferenceMessage("user", "context")));
        when(renderer.renderRepair(any(), any(), any())).thenReturn(List.of(
                new ModelInferenceMessage("system", "system"),
                new ModelInferenceMessage("user", "repair")));
        ModelInferenceGateway stub = request -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                first.set(request);
            } else {
                second.set(request);
            }
            String content = scripts.poll();
            if (content == null) {
                throw new IllegalStateException("boom");
            }
            return new ModelInferenceResponse(content, "stop", 0, 0);
        };
        return new GlobalAssistantBrain(renderer, stub,
                new GlobalAssistantDecisionParser(new ObjectMapper()),
                new GlobalAssistantDecisionValidator());
    }

    @Test
    void invalidThenValidRepairsOnce() {
        Queue<String> scripts = new ArrayDeque<>(List.of(
                "not json at all",
                VALID_FINAL));
        AtomicReference<ModelInferenceRequest> first = new AtomicReference<>();
        AtomicReference<ModelInferenceRequest> second = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        // repair path is exercised at runtime level; here the brain parses strict single calls
        GlobalAssistantBrain brain = brainWithScripts(scripts, first, second, calls);
        assertThatThrownBy(() -> brain.decide(UUID.randomUUID(), null, List.of()))
                .isInstanceOf(GlobalAssistantModelException.class);
        GlobalAssistantDecision repaired =
                brain.repairDecision(UUID.randomUUID(), null, List.of(), "not json");
        assertThat(repaired.kind()).isEqualTo(GlobalAssistantDecision.DecisionKind.FINAL);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(second.get().callType()).isEqualTo(GlobalAssistantBrain.DECISION_REPAIR_CALL_TYPE);
        assertThat(second.get().outputContract()).isInstanceOf(ModelOutputContract.JsonObject.class);
    }

    @Test
    void repairCallUsesSameRunIdAndContract() {
        UUID runId = UUID.randomUUID();
        Queue<String> scripts = new ArrayDeque<>(List.of(VALID_FINAL));
        AtomicReference<ModelInferenceRequest> first = new AtomicReference<>();
        AtomicReference<ModelInferenceRequest> second = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        GlobalAssistantBrain brain = brainWithScripts(scripts, first, second, calls);
        brain.repairDecision(runId, null, List.of(), "missing");
        assertThat(first.get().runId()).isEqualTo(runId);
        assertThat(first.get().callType()).isEqualTo(GlobalAssistantBrain.DECISION_REPAIR_CALL_TYPE);
        assertThat(first.get().outputContract()).isInstanceOf(ModelOutputContract.JsonObject.class);
    }

    @Test
    void blankCompletionIsRepairEligible() {
        Queue<String> scripts = new ArrayDeque<>(List.of("   ", VALID_FINAL));
        AtomicReference<ModelInferenceRequest> first = new AtomicReference<>();
        AtomicReference<ModelInferenceRequest> second = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        GlobalAssistantBrain brain = brainWithScripts(scripts, first, second, calls);
        assertThatThrownBy(() -> brain.decide(UUID.randomUUID(), null, List.of()))
                .matches(ex -> "MODEL_INVALID_RESPONSE".equals(((GlobalAssistantModelException) ex).errorCode()));
        GlobalAssistantDecision repaired =
                brain.repairDecision(UUID.randomUUID(), null, List.of(), "empty");
        assertThat(repaired.kind()).isEqualTo(GlobalAssistantDecision.DecisionKind.FINAL);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void legacyFieldIsRepairEligible() {
        Queue<String> scripts = new ArrayDeque<>(List.of(
                """
                {"kind":"FINAL","assistantText":"Hi.","done":true}
                """,
                VALID_FINAL));
        AtomicReference<ModelInferenceRequest> first = new AtomicReference<>();
        AtomicReference<ModelInferenceRequest> second = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        GlobalAssistantBrain brain = brainWithScripts(scripts, first, second, calls);
        assertThatThrownBy(() -> brain.decide(UUID.randomUUID(), null, List.of()))
                .isInstanceOf(GlobalAssistantModelException.class);
        GlobalAssistantDecision repaired =
                brain.repairDecision(UUID.randomUUID(), null, List.of(), "legacy");
        assertThat(repaired.kind()).isEqualTo(GlobalAssistantDecision.DecisionKind.FINAL);
        assertThat(second.get().callType()).isEqualTo(GlobalAssistantBrain.DECISION_REPAIR_CALL_TYPE);
    }

    @Test
    void providerFailureIsNotRepairedAtBrainLevel() {
        Queue<String> scripts = new ArrayDeque<>();
        AtomicReference<ModelInferenceRequest> first = new AtomicReference<>();
        AtomicReference<ModelInferenceRequest> second = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        GlobalAssistantBrain brain = brainWithScripts(scripts, first, second, calls);
        assertThatThrownBy(() -> brain.decide(UUID.randomUUID(), null, List.of()))
                .matches(ex -> "MODEL_UNAVAILABLE".equals(((GlobalAssistantModelException) ex).errorCode()));
        assertThat(calls.get()).isEqualTo(1);
    }
}
