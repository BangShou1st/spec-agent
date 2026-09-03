package com.specagent.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E01 — Simple Answer (smoke scenario, P2 corpus).
 *
 * <p>Verifies the most basic production answer cycle: the Answer persists,
 * the STATE_UPDATE patch passes Java validation/application, a post-state
 * ContextSnapshot feeds DECISION, the primary action is allowed, no
 * unrelated state mutates, and the call budget holds.
 */
class E01SimpleAnswerTest extends EvalHarnessBase {

    @Test
    void baseVariantPassesFullCycle() {
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.passed()).isTrue();
        assertThat(observation.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
        assertThat(observation.productionModelCalls()).isEqualTo(2);
        assertThat(observation.providerRetries()).isZero();
        assertThat(observation.stateDelta()).containsEntry("answers", 1);
        assertThat(observation.stateDelta()).containsEntry("patches", 1);
    }

    @Test
    void paraphrasedVariantPassesFullCycle() {
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("paraphrase"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }

    @Test
    void shuffledContextVariantPassesFullCycle() {
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("shuffled"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }
}
