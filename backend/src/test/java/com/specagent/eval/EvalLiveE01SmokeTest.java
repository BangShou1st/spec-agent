package com.specagent.eval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** One-attempt E01 live smoke; intentionally separate from the full baseline task. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18082",
                "spec.agent.brain.engine=remote-python",
                "spec.agent.brain.base-url=${SPEC_AGENT_EVAL_BRAIN_BASE_URL:http://localhost:8100}",
                "spec.agent.brain.internal-secret=${SPEC_AGENT_BRAIN_INTERNAL_SECRET:dev-internal-secret}",
                "spec.agent.model.inference=opencode",
                "spec.agent.model.runtime-settings-source=external-environment",
                "spec.agent.model.external.api-key=${SPEC_AGENT_EVAL_OPENCODE_KEY:}",
                "spec.agent.model.external.selected-model=${SPEC_AGENT_EVAL_OPENCODE_MODEL:}",
                "spec.agent.model.opencode.base-url=https://opencode.ai/zen/v1"
        })
class EvalLiveE01SmokeTest extends EvalLiveHarnessBase {

    private static final String BRAIN_HEALTH = System.getenv().getOrDefault(
            "SPEC_AGENT_EVAL_BRAIN_BASE_URL", "http://localhost:8100") + "/health";

    @Test
    void oneE01AttemptUsesTheRealProductionChain() {
        LiveChainEvidence before = requireLiveBrain(BRAIN_HEALTH);
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runLiveScenario(scenario, variant, 1).get(0);
        LiveBrainHealth after = readLiveBrainHealth(BRAIN_HEALTH);

        assertThat(before.javaWiring().decisionEngine())
                .isEqualTo("com.specagent.agent.decision.RemotePythonDecisionEngine");
        assertThat(before.javaWiring().inferenceGateway())
                .isEqualTo("com.specagent.model.inference.OpenCodeModelInferenceGateway");
        assertThat(before.pythonBefore().modelMode()).isEqualTo("broker");
        assertThat(before.endpoint()).isEqualTo("https://opencode.ai/zen/v1");
        assertThat(before.credentialSource())
                .isEqualTo("external-environment:SPEC_AGENT_EVAL_OPENCODE_KEY");
        assertThat(before.selectedModel()).isNotBlank().endsWith("-free");
        assertThat(after.stateUpdates()).isGreaterThan(before.pythonBefore().stateUpdates());
        assertThat(after.decisions()).isGreaterThan(before.pythonBefore().decisions());
        assertThat(observation.scenarioId()).isEqualTo("E01");
        assertThat(observation.executionResult()).isEqualTo("completed");
        assertThat(observation.productionModelCalls()).isEqualTo(2);
        assertThat(observation.providerRetries()).isZero();
        assertThat(observation.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
        assertThat(observation.stateDelta()).containsEntry("answers", 1)
                .containsEntry("patches", 1);

        System.out.println("Live E01 smoke: gateway=" + before.javaWiring().inferenceGateway()
                + " endpoint=" + before.endpoint() + " model=" + before.selectedModel()
                + " credential_source=" + before.credentialSource()
                + " state_updates_delta="
                + (after.stateUpdates() - before.pythonBefore().stateUpdates())
                + " decisions_delta="
                + (after.decisions() - before.pythonBefore().decisions())
                + " production_model_calls=" + observation.productionModelCalls()
                + " provider_retries=" + observation.providerRetries()
                + " result=" + observation.executionResult());
    }
}
