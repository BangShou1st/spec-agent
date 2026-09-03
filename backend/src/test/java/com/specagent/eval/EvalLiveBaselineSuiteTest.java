package com.specagent.eval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P2 Phase 2 — Live agent behavioral baseline (non-blocking).
 *
 * <p>Runs the same Scenario Contract through the real {@code Python Brain +
 * live provider} chain: identical canonical Java runtime setup, identical
 * Layer A invariants, identical Layer B expectations, identical call budget.
 * The scenario's scripted {@code given.brainScript} is never installed and
 * never consulted — the production Brain wiring answers STATE_UPDATE +
 * DECISION.
 *
 * <p>Each scenario variant repeats {@value #LIVE_REPETITIONS} times; the
 * recorded artifact ({@code results.jsonl}, {@code stability.json},
 * {@code stability.txt} under {@code build/eval-live}) captures the raw
 * behavioral baseline. The suite records reality and never asserts pass —
 * a red baseline must not trigger prompt tuning by itself, and live-provider
 * flakiness must never block PR CI.
 *
 * <p>Requires a running agent-brain in broker mode plus a configured OpenCode
 * provider behind the Java broker; otherwise the suite is skipped.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18082",
                "spec.agent.brain.engine=remote-python",
                "spec.agent.brain.base-url=${SPEC_AGENT_EVAL_BRAIN_BASE_URL:http://localhost:8100}",
                "spec.agent.brain.internal-secret=${SPEC_AGENT_BRAIN_INTERNAL_SECRET:dev-internal-secret}",
                "spec.agent.model.inference=opencode"
        })
class EvalLiveBaselineSuiteTest extends EvalLiveHarnessBase {

    private static final String BRAIN_HEALTH = System.getenv().getOrDefault(
            "SPEC_AGENT_EVAL_BRAIN_BASE_URL", "http://localhost:8100") + "/health";

    @Test
    void recordLiveBehavioralBaseline() throws Exception {
        LiveChainEvidence before = requireLiveBrain(BRAIN_HEALTH);

        List<ObservationEnvelope> observations = new ArrayList<>();
        for (ScenarioDefinition scenario : liveCorpus()) {
            for (VariantSpec variant : scenario.variants()) {
                observations.addAll(runLiveScenario(scenario, variant, LIVE_REPETITIONS));
            }
        }

        String runId = UUID.randomUUID().toString();
        String gitSha = readGitSha();
        List<ObservationEnvelope> stamped = new ArrayList<>();
        for (ObservationEnvelope observation : observations) {
            stamped.add(observation.withRunMetadata(
                    runId, gitSha, liveProvider(), before.selectedModel(), liveModelConfigDigest()));
        }

        Path outputDir = Path.of(System.getProperty("user.dir"), "build", "eval-live");
        Files.createDirectories(outputDir);
        StringBuilder jsonl = new StringBuilder();
        for (ObservationEnvelope observation : stamped) {
            jsonl.append(EvalArtifactWriter.toJsonl(observation)).append("\n");
        }
        Files.writeString(outputDir.resolve("results.jsonl"), jsonl.toString(),
                StandardCharsets.UTF_8);

        LiveStabilitySummary stability = LiveStabilitySummary.from(stamped, LIVE_REPETITIONS);
        LiveBrainHealth after = readLiveBrainHealth(BRAIN_HEALTH);
        assumeTrue(after.stateUpdates() >= before.pythonBefore().stateUpdates()
                        && after.decisions() >= before.pythonBefore().decisions(),
                "agent-brain invocation counters reset during the live baseline");
        LiveChainEvidence evidence = before.withPythonAfter(after);
        java.util.Map<String, Object> stabilityReport = stabilityToMap(stability);
        stabilityReport.put("live_chain_evidence", evidenceToMap(evidence));
        Files.writeString(outputDir.resolve("stability.json"),
                EvalArtifactWriter.toJson(stabilityReport), StandardCharsets.UTF_8);
        String header = "# eval live baseline " + Instant.now() + " run=" + runId
                + " git=" + gitSha + " provider=" + liveProvider()
                + " model=" + before.selectedModel() + "\n"
                + liveEvidenceText(evidence);
        Files.writeString(outputDir.resolve("stability.txt"), header + stability.toText(),
                StandardCharsets.UTF_8);

        System.out.println("Eval live baseline: " + outputDir.toAbsolutePath());
        System.out.println(stability.toText());

        // Non-blocking by design: record the baseline, do not gate on it.
        // The printed stability report is the deliverable; failures here are
        // behavioral data for Phase 3 triage, not CI signals.
    }

    /**
     * Live corpus: the already-frozen B-fast scenarios (smoke, conflict,
     * confirmation, frozen context) plus the verified batch-2 scenarios.
     * Every expectation is the unchanged Scenario Contract — no scenario is
     * relaxed for live. Batch-2 scenarios passed B-fast first, so a live red
     * always means live behavior, never a malformed scenario.
     */
    static List<ScenarioDefinition> liveCorpus() {
        List<ScenarioDefinition> corpus = new ArrayList<>(List.of(
                EvalCorpus.e01(),
                EvalCorpus.e07(),
                EvalCorpus.e07Resolved(),
                EvalCorpus.e17(),
                EvalCorpus.e25()));
        corpus.addAll(EvalCorpusBatch2.all());
        return List.copyOf(corpus);
    }

    /** Provider identity for the run stamp; never a secret or key material. */
    private static String liveProvider() {
        return "opencode-zen";
    }

    private static String liveModelConfigDigest() {
        return "unknown";
    }

    private static java.util.Map<String, Object> stabilityToMap(LiveStabilitySummary stability) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("total_attempts", stability.totalAttempts());
        map.put("passed", stability.passed());
        map.put("failed", stability.failed());
        map.put("pass_rate", stability.passRate());
        map.put("layer_a_pass_rate", stability.layerAPassRate());
        map.put("stability", stability.stability());
        java.util.Map<String, Integer> failures = new java.util.LinkedHashMap<>();
        stability.failureCounts().forEach((key, value) -> failures.put(key.name(), value));
        map.put("failure_counts", failures);
        map.put("primary_action_distribution",
                new java.util.TreeMap<>(stability.primaryActionDistribution()));
        map.put("token_totals", stability.tokenTotals());
        map.put("total_latency_ms", stability.totalLatencyMs());
        map.put("production_model_calls", stability.productionModelCalls());
        map.put("provider_retries", stability.providerRetries());
        map.put("capability_calls", stability.capabilityCalls());
        map.put("scenario_results", stability.scenarioResults());
        map.put("notes", stability.notes());
        return map;
    }

    private static java.util.Map<String, Object> evidenceToMap(LiveChainEvidence evidence) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("java_decision_engine", evidence.javaWiring().decisionEngine());
        map.put("java_inference_gateway", evidence.javaWiring().inferenceGateway());
        map.put("python_protocol", evidence.pythonBefore().protocolVersion());
        map.put("python_model_mode", evidence.pythonBefore().modelMode());
        map.put("observed_state_update_requests", evidence.pythonBefore().stateUpdates());
        map.put("observed_decision_requests", evidence.pythonBefore().decisions());
        map.put("last_state_update_run_id", evidence.pythonBefore().lastStateUpdateRunId());
        map.put("last_decision_run_id", evidence.pythonBefore().lastDecisionRunId());
        map.put("selected_model", evidence.selectedModel());
        return map;
    }

    private static String liveEvidenceText(LiveChainEvidence evidence) {
        return "live_chain: java_engine=" + evidence.javaWiring().decisionEngine()
                + " java_inference_gateway=" + evidence.javaWiring().inferenceGateway()
                + " python_mode=" + evidence.pythonBefore().modelMode()
                + " state_update_requests=" + evidence.pythonBefore().stateUpdates()
                + " decision_requests=" + evidence.pythonBefore().decisions()
                + " selected_model=" + evidence.selectedModel() + "\n";
    }

    private static String readGitSha() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            process.waitFor();
            return output.isEmpty() ? "unknown" : output;
        } catch (Exception ex) {
            return "unknown";
        }
    }
}
