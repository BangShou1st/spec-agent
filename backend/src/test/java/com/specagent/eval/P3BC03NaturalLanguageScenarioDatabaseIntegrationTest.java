package com.specagent.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Scripted brain, but real Spring/database: never include in an offline-only test selection. */
class P3BC03NaturalLanguageScenarioDatabaseIntegrationTest extends EvalHarnessBase {

    @Test
    void bfastExecutionSucceedsForNaturalLanguageC03() {
        ObservationEnvelope obs = runScenario(
                P3BC03NaturalLanguageScenarios.rewordedRepeatScenario().definition(),
                VariantSpec.base("base", 33501L));
        assertThat(obs.executionResult()).isEqualTo("completed");
        assertThat(obs.actualPrimaryAction()).isEqualTo("RESPOND_TO_USER");
        assertThat(obs.violations()).isEmpty();
    }
}
