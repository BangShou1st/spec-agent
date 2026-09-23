package com.specagent.eval;

import com.specagent.agent.trace.SemanticTraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same deterministic scenario, with semantic tracing disabled and enabled.
 * The only tolerated differences are runtime-generated UUIDs/context hashes;
 * all observable behavior and the exact serialized model request remain the
 * same after those identities are normalized for comparison.
 *
 * <p><b>Isolation note (intermittent failure, 2026-09-20).</b> In full-suite
 * runs this test occasionally failed with the "on" attempt producing zero
 * brain requests. The run then reported
 * {@code failed:IllegalStateException: No queued answer-cycle run}: the
 * harness had enqueued its own ANSWER_CYCLE run, but the shared-queue claim
 * ({@code claimNextAnswerCycle}, "oldest queued run in the whole table") found
 * nothing claimable at that instant. The scenario is otherwise deterministic:
 * this class passes in isolation and the whole eval package passed 5/5
 * consecutive runs, so the trigger is cross-test state in the same JVM/DB,
 * not scenario semantics.
 *
 * <p>Fix: the harness now claims the exact run it enqueued
 * ({@code RunService.claimAnswerCycleRun(runId)}, the same by-id guard
 * {@code AnswerCycleTestDriver} already used), so a shared queue can neither
 * hand it somebody else's run nor hide its own behind another test's ordering.
 * The execution result is also compared before the request payloads, so any
 * residual failure names its own cause instead of an empty-vs-one-list diff.
 *
 * <p>Residual uncertainty / follow-up: the exact interleaving was not
 * reproduced on demand. Ruled out here: {@code ScriptedBrain.requestPayloads}
 * leaking across attempts (it is cleared by {@code install()} on every run)
 * and a leaked background {@code RunWorkerPoller} (the worker-enabled test
 * contexts polling the shared DB were exercised with a 50 ms poll interval
 * immediately before this package and produced no interference).
 */
class SemanticTraceBehaviorPreservationIntegrationTest extends EvalHarnessBase {

    @Autowired
    private SemanticTraceRecorder semanticTraceRecorder;

    @AfterEach
    void restoreTraceSetting() {
        semanticTraceRecorder.setEnabledForTesting(true);
    }

    @Test
    void tracingDoesNotChangeRequestsCallsStateActionsOrEvaluation() {
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().get(0);

        semanticTraceRecorder.setEnabledForTesting(false);
        ObservationEnvelope off = runScenario(scenario, variant);
        List<String> offRequests = scriptedBrain.requestPayloads();
        java.util.UUID offProject = scenarioRunner.lastProjectId();
        cleanUpProject(offProject);

        semanticTraceRecorder.setEnabledForTesting(true);
        ObservationEnvelope on = runScenario(scenario, variant);
        List<String> onRequests = scriptedBrain.requestPayloads();

        // Compared before the payload sequences: when the "on" attempt never
        // reaches the brain, the execution result pinpoints the cause instead
        // of an opaque empty-vs-one-payload list mismatch.
        assertThat(on.executionResult())
                .as("on attempt must execute identically (off=%s, on violations=%s)",
                        off.executionResult(), on.violations())
                .isEqualTo(off.executionResult());
        assertThat(onRequests.stream().map(this::normalizeDynamicIdentity).toList())
                .containsExactlyElementsOf(offRequests.stream()
                        .map(this::normalizeDynamicIdentity).toList());
        assertThat(on.productionModelCalls()).isEqualTo(off.productionModelCalls());
        assertThat(on.providerRetries()).isEqualTo(off.providerRetries());
        assertThat(on.actualPrimaryAction()).isEqualTo(off.actualPrimaryAction());
        assertThat(on.executionResult()).isEqualTo(off.executionResult());
        assertThat(on.stateDelta()).isEqualTo(off.stateDelta());
        assertThat(on.violations()).isEqualTo(off.violations());
        assertThat(off.semanticTrace().stages()).isEmpty();
        assertThat(on.semanticTrace().stages()).containsKeys(
                "STATE_UPDATE_INPUT", "STATE_UPDATE_OUTPUT",
                "POST_STATE_UPDATE_STATE", "DECISION_INPUT", "DECISION_OUTPUT",
                "ACTION_ELIGIBILITY", "FINAL_RESULT");
        assertThat(on.semanticTrace().stages().get("ACTION_ELIGIBILITY"))
                .containsEntry("mode", "SHADOW")
                .containsEntry("selected_action", on.actualPrimaryAction());
    }

    private String normalizeDynamicIdentity(String value) {
        return value.replaceAll(
                        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        "<uuid>")
                .replaceAll("(?i)[0-9a-f]{64}", "<hash>");
    }
}
