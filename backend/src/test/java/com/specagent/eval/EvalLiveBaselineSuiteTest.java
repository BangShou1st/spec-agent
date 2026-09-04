package com.specagent.eval;

import com.specagent.common.Hashes;
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
 * behavioral results must never block PR CI.
 *
 * <p>Requires a running agent-brain in broker mode plus explicit external
 * OpenCode configuration. Missing/invalid provider settings fail before the
 * first scenario; an unavailable brain is skipped.
 */
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
                "spec.agent.model.opencode.base-url=https://opencode.ai/zen/v1",
                "spec.agent.semantic-trace.enabled=true"
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
                    runId, gitSha, liveProvider(), before.selectedModel(), liveModelConfigDigest(before)));
        }

        Path outputDir = Path.of(System.getProperty("user.dir"), "build", "eval-live");
        Files.createDirectories(outputDir);
        StringBuilder jsonl = new StringBuilder();
        for (ObservationEnvelope observation : stamped) {
            jsonl.append(EvalArtifactWriter.toJsonl(observation)).append("\n");
        }
        Files.writeString(outputDir.resolve("results.jsonl"), jsonl.toString(),
                StandardCharsets.UTF_8);

        int plannedAttempts = liveCorpus().stream()
                .mapToInt(scenario -> scenario.variants().size() * LIVE_REPETITIONS)
                .sum();
        LiveStabilitySummary stability = LiveStabilitySummary.from(
                stamped, LIVE_REPETITIONS, plannedAttempts);
        LiveBrainHealth after = readLiveBrainHealth(BRAIN_HEALTH);
        assumeTrue(after.stateUpdates() >= before.pythonBefore().stateUpdates()
                        && after.decisions() >= before.pythonBefore().decisions(),
                "agent-brain invocation counters reset during the live baseline");
        LiveChainEvidence evidence = before.withPythonAfter(after);
        java.util.Map<String, Object> stabilityReport = stabilityToMap(stability, stamped);
        stabilityReport.put("live_chain_evidence", evidenceToMap(evidence));
        Files.writeString(outputDir.resolve("stability.json"),
                EvalArtifactWriter.toJson(stabilityReport), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("metadata.json"),
                EvalArtifactWriter.toJson(metadata(runId, gitSha, before, evidence,
                        plannedAttempts, stamped)), StandardCharsets.UTF_8);
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

    private static String liveModelConfigDigest(LiveChainEvidence evidence) {
        return Hashes.sha256Hex(evidence.endpoint() + "\n"
                + evidence.selectedModel() + "\n" + evidence.credentialSource());
    }

    private static java.util.Map<String, Object> stabilityToMap(
            LiveStabilitySummary stability, List<ObservationEnvelope> observations) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("planned_attempts", stability.plannedAttempts());
        map.put("executed_attempts", stability.totalAttempts());
        map.put("behavioral_completed", stability.behavioralCompleted());
        map.put("behavioral_passed", stability.behavioralPassed());
        map.put("behavioral_failed", stability.behavioralFailed());
        map.put("behavioral_pass_rate", stability.behavioralPassRate());
        map.put("infrastructure_failed", stability.infrastructureFailed());
        map.put("availability_rate", stability.availabilityRate());
        map.put("layer_a_pass_rate", stability.layerAPassRate());
        map.put("behavioral_stability", stability.stability());
        java.util.Map<String, Integer> failures = new java.util.LinkedHashMap<>();
        stability.failureCounts().forEach((key, value) -> failures.put(key.name(), value));
        map.put("behavioral_failure_counts", failures);
        java.util.Map<String, Integer> providerFailures = new java.util.LinkedHashMap<>();
        stability.providerFailureClasses().forEach((key, value) -> providerFailures.put(key.name(), value));
        map.put("provider_failure_classes", providerFailures);
        map.put("schema_failures", observations.stream()
                .filter(LiveFailureClassifier::isSchemaFailure).count());
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

    private static java.util.Map<String, Object> metadata(
            String runId, String gitSha, LiveChainEvidence before,
            LiveChainEvidence evidence, int plannedAttempts,
            List<ObservationEnvelope> observations) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("schema_version", "eval-live-baseline.v2");
        map.put("run_id", runId);
        map.put("git_sha", gitSha);
        map.put("evaluation_arm", System.getProperty("spec.agent.eval.arm", "UNSPECIFIED"));
        map.put("prompt_revision", System.getenv().getOrDefault(
                "SPEC_AGENT_EVAL_PROMPT_COMMIT", gitSha));
        map.put("scenario_corpus_identity", corpusIdentity(liveCorpus()));
        map.put("planned_attempts", plannedAttempts);
        map.put("repetitions_per_variant", LIVE_REPETITIONS);
        map.put("provider_model_provenance", java.util.Map.of(
                "provider", "opencode-zen",
                "endpoint", before.endpoint(),
                "user_agent", com.specagent.model.provider.OpenCodeZenTransport.USER_AGENT,
                "model", before.selectedModel(),
                "credential_source", before.credentialSource(),
                "java_engine", before.javaWiring().decisionEngine(),
                "java_inference_gateway", before.javaWiring().inferenceGateway(),
                "python_protocol", before.pythonBefore().protocolVersion(),
                "python_mode", before.pythonBefore().modelMode()));
        map.put("prompt_hashes", EvalArtifactWriter.promptProvenance(observations));
        map.put("provider_health_before", evidenceToMap(before));
        map.put("provider_health_after", evidenceToMap(evidence));
        return map;
    }

    private static String corpusIdentity(List<ScenarioDefinition> scenarios) {
        String canonical = scenarios.stream()
                .map(scenario -> scenario.scenarioId() + ":" + scenario.scenarioHash())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        return Hashes.sha256Hex(canonical);
    }

    private static java.util.Map<String, Object> evidenceToMap(LiveChainEvidence evidence) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("java_decision_engine", evidence.javaWiring().decisionEngine());
        map.put("java_inference_gateway", evidence.javaWiring().inferenceGateway());
        map.put("provider_endpoint", evidence.endpoint());
        map.put("credential_source", evidence.credentialSource());
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
                + " endpoint=" + evidence.endpoint()
                + " python_mode=" + evidence.pythonBefore().modelMode()
                + " state_update_requests=" + evidence.pythonBefore().stateUpdates()
                + " decision_requests=" + evidence.pythonBefore().decisions()
                + " credential_source=" + evidence.credentialSource()
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
