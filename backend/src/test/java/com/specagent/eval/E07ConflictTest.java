package com.specagent.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E07 — Conflict (P2 corpus).
 *
 * <p>Variant A (unresolved): incompatible demands must enter explicit
 * resolution (REQUEST_USER_INPUT), never a silent priority choice.
 * Variant B (resolved, E07-resolved): the user already made the
 * tradeoff, so the agent must converge without re-asking.
 */
class E07ConflictTest extends EvalHarnessBase {

    @Test
    void unresolvedConflictEntersExplicitResolution() {
        ScenarioDefinition scenario = EvalCorpus.e07();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.violations())
                .as("E07 unresolved violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
    }

    @Test
    void unresolvedParaphraseVariantEntersExplicitResolution() {
        ScenarioDefinition scenario = EvalCorpus.e07();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("unresolved-paraphrase"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }

    @Test
    void resolvedConflictConvergesWithoutReasking() {
        ScenarioDefinition scenario = EvalCorpus.e07Resolved();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.violations())
                .as("E07 resolved violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.actualPrimaryAction()).isEqualTo("RESPOND_TO_USER");
    }

    @Test
    void resolvedParaphraseVariantConverges() {
        ScenarioDefinition scenario = EvalCorpus.e07Resolved();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("resolved-paraphrase"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }
}
