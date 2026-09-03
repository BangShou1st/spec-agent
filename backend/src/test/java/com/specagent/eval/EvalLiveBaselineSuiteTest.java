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
                "spec.agent.brain.base-url=http://localhost:8100"
        })
class EvalLiveBaselineSuiteTest extends EvalLiveHarnessBase {

    private static final String BRAIN_HEALTH = "http://localhost:8100/health";

    @Test
    void recordLiveBehavioralBaseline() throws Exception {
        requireLiveBrain(BRAIN_HEALTH);

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
                    runId, gitSha, liveProvider(), liveModel(), liveModelConfigDigest()));
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
        Files.writeString(outputDir.resolve("stability.json"),
                EvalArtifactWriter.toJson(stabilityToMap(stability)), StandardCharsets.UTF_8);
        String header = "# eval live baseline " + Instant.now() + " run=" + runId
                + " git=" + gitSha + " provider=" + liveProvider()
                + " model=" + liveModel() + "\n";
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

    /** Model identity if the runtime exposes it, else unknown — never guessed. */
    private static String liveModel() {
        return "unknown";
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
