package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Second corpus batch (P2 Phase 2) — B-fast verification.
 *
 * <p>Every batch-2 scenario must pass scripted before entering the live
 * baseline corpus, so a live red always means live behavior — never a
 * malformed scenario. Capability scenarios additionally assert the probe
 * invocation counts the contract implies.
 */
class EvalBatch2CorpusTest extends EvalHarnessBase {

    @Test
    void e02AmbiguityClarifiesExplicitly() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e02();
        for (VariantSpec variant : scenario.variants()) {
            assertPasses(scenario, variant);
        }
    }

    @Test
    void e05SiblingRouteStaysUnpolluted() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e05();
        for (VariantSpec variant : scenario.variants()) {
            ObservationEnvelope observation = runScenario(scenario, variant);
            assertThat(observation.violations())
                    .as("E05 violations: %s", observation.violations())
                    .isEmpty();
            assertThat(observation.stateDelta()).containsEntry("routes", 0);
        }
    }

    @Test
    void e08DelegatedTradeoffRequiresConfirmation() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e08();
        for (VariantSpec variant : scenario.variants()) {
            ObservationEnvelope observation = runScenario(scenario, variant);
            assertThat(observation.violations())
                    .as("E08 violations: %s", observation.violations())
                    .isEmpty();
            assertThat(observation.actualPrimaryAction()).isEqualTo("CREATE_NODE");
            assertThat(observation.executionResult()).startsWith("awaiting_approval:");
        }
    }

    @Test
    void e09RoutePlanningDeniedWithoutExecutablePath() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e09();
        ObservationEnvelope observation = runScenario(scenario, scenario.variants().get(0));

        assertThat(observation.violations())
                .as("E09 violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.actualPrimaryAction()).isEqualTo("CREATE_ROUTE");
        assertThat(observation.executionResult()).isEqualTo("policy_denied");
    }

    @Test
    void e10ResourceGroundingCompletesNormally() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e10();
        for (VariantSpec variant : scenario.variants()) {
            assertPasses(scenario, variant);
        }
    }

    @Test
    void e11ReadOnlyCapabilityExecutesExactlyOnce() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e11();
        ObservationEnvelope observation = runScenario(scenario, scenario.variants().get(0));

        assertThat(observation.violations())
                .as("E11 violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.actualPrimaryAction()).isEqualTo("INVOKE_CAPABILITY");
        assertThat(EvalProbeCapabilities.invocationsOf(
                EvalProbeCapabilities.DECOY_READ_ONLY)).isEqualTo(1);
    }

    @Test
    void e12FailingCapabilitySurfacesWithoutRetry() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e12();
        ObservationEnvelope observation = runScenario(scenario, scenario.variants().get(0));

        assertThat(observation.violations())
                .as("E12 violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.actualPrimaryAction()).isEqualTo("INVOKE_CAPABILITY");
        assertThat(observation.executionResult()).isEqualTo("completed");
        assertThat(EvalProbeCapabilities.invocationsOf(
                EvalProbeCapabilities.DECOY_READ_ONLY)).isEqualTo(1);
    }

    @Test
    void e13IrrelevantCapabilitiesStayUninvoked() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e13();
        for (VariantSpec variant : scenario.variants()) {
            ObservationEnvelope observation = runScenario(scenario, variant);
            assertThat(observation.violations())
                    .as("E13 violations: %s", observation.violations())
                    .isEmpty();
            assertThat(EvalProbeCapabilities.invocationCounts()).isEmpty();
        }
    }

    @Test
    void e19LargeContextCompletesWithOneMutation() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e19();
        for (VariantSpec variant : scenario.variants()) {
            assertPasses(scenario, variant);
        }
    }

    @Test
    void e22LegitimateWaitPausesWithoutMutation() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e22Wait();
        ObservationEnvelope observation = runScenario(scenario, scenario.variants().get(0));

        assertThat(observation.violations())
                .as("E22-wait violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.actualPrimaryAction()).isEqualTo("WAIT");
        assertThat(observation.stateDelta()).containsEntry("nodes", 0);
    }

    @Test
    void batch2ScenariosValidateWithStableUniqueHashes() {
        Set<String> hashes = new HashSet<>();
        for (ScenarioDefinition scenario : EvalCorpusBatch2.all()) {
            scenario.validate();
            String hash = scenario.scenarioHash();
            assertThat(hash).isNotBlank();
            assertThat(hashes.add(hash))
                    .as("duplicate scenario hash: %s", scenario.scenarioId())
                    .isTrue();
            assertThat(scenario.expect().callBudget()).isNotNull();
            assertThat(scenario.expect().callBudget().maxProductionCalls())
                    .as("scenario %s budget", scenario.scenarioId())
                    .isLessThanOrEqualTo(2);
        }
    }

    @Test
    void e24FocusCannotChangeAnswerOwnership() {
        ScenarioDefinition scenario = EvalCorpusBatch2.e24();
        for (VariantSpec variant : scenario.variants()) {
            ObservationEnvelope observation = runScenario(scenario, variant);
            assertThat(observation.violations())
                    .as("E24 violations: %s", observation.violations())
                    .isEmpty();
            assertThat(observation.stateDelta()).containsEntry("answers", 1);
        }
    }
}
