package com.specagent.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E06 — Shared State (P2 corpus).
 *
 * <p>Variant A (shared read): covered by production route-isolation suites;
 * here the harness proves the negative — Variant B: a forked route that
 * attempts a divergent second answer on the same canonical Question must
 * fail closed (SHARED_STATE_DIVERGENCE), with no forked canonical state
 * and no silent second identity.
 */
class E06SharedStateTest extends EvalHarnessBase {

    @Test
    void divergentAnswerFailsClosedWithoutForkingCanonicalState() {
        ScenarioDefinition scenario = EvalCorpus.e06();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.violations())
                .as("E06 divergent violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.executionResult()).startsWith("failed:");
        assertThat(observation.executionResult()).contains("SHARED_STATE_DIVERGENCE");
        assertThat(observation.stateDelta()).containsEntry("answers", 0);
        assertThat(observation.stateDelta()).containsEntry("patches", 0);
        assertThat(observation.stateDelta()).containsEntry("nodes", 0);
    }

    @Test
    void divergentParaphraseVariantFailsClosed() {
        ScenarioDefinition scenario = EvalCorpus.e06();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("divergent-paraphrase"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }
}
