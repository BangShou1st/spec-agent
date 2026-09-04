package com.specagent.eval;

import com.specagent.common.Hashes;
import com.specagent.trace.SemanticTrace;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3B targeted live diagnostic rerun. This is deliberately a separate
 * opt-in task and artifact directory; it never rewrites the formal 90-attempt
 * baseline under {@code build/eval-live}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18083",
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
class EvalLiveDiagnosticSuiteTest extends EvalLiveHarnessBase {

    private static final String BASELINE_REFERENCE_COMMIT =
            "464097cd6ca86a0107ce369214506484dfd57c3f";
    private static final int DIAGNOSTIC_REPETITIONS = 3;
    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @Test
    void runTargetedSemanticDiagnosticSuite() throws Exception {
        LiveChainEvidence before = requireLiveBrain(
                System.getenv().getOrDefault("SPEC_AGENT_EVAL_BRAIN_BASE_URL",
                        "http://localhost:8100") + "/health");
        List<ScenarioDefinition> scenarios = targetedCorpus();
        List<ObservationEnvelope> observations = new ArrayList<>();
        for (ScenarioDefinition scenario : scenarios) {
            for (VariantSpec variant : scenario.variants()) {
                observations.addAll(runLiveScenario(scenario, variant,
                        DIAGNOSTIC_REPETITIONS));
            }
        }

        String instrumentationCommit = readGitSha();
        String diagnosticRunId = java.util.UUID.randomUUID().toString();
        List<ObservationEnvelope> stamped = observations.stream()
                .map(observation -> observation
                        .withDiagnosticMetadata(BASELINE_REFERENCE_COMMIT, instrumentationCommit)
                        .withRunMetadata(diagnosticRunId, instrumentationCommit,
                                liveProvider(), before.selectedModel(),
                                liveModelConfigDigest(before)))
                .toList();

        Map<String, ScenarioDefinition> scenarioMap = new LinkedHashMap<>();
        scenarios.forEach(scenario -> scenarioMap.put(scenario.scenarioId(), scenario));
        CausalReportGenerator.CausalReport causal =
                CausalReportGenerator.generate(stamped, scenarioMap);
        LiveStabilitySummary stability = LiveStabilitySummary.from(
                stamped, DIAGNOSTIC_REPETITIONS);
        LiveBrainHealth after = readLiveBrainHealth(
                System.getenv().getOrDefault("SPEC_AGENT_EVAL_BRAIN_BASE_URL",
                        "http://localhost:8100") + "/health");
        assumeHealthMonotonic(before, after);

        Path outputDir = Path.of(System.getProperty("user.dir"), "build",
                "eval-live-diagnostic",
                DIRECTORY_TIME.format(Instant.now()) + "-" + instrumentationCommit);
        Files.createDirectories(outputDir);
        StringBuilder jsonl = new StringBuilder();
        for (ObservationEnvelope observation : stamped) {
            jsonl.append(EvalArtifactWriter.toJsonl(observation)).append('\n');
        }
        Files.writeString(outputDir.resolve("results.jsonl"), jsonl.toString(),
                StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("stability.json"),
                EvalArtifactWriter.toJson(stabilityMap(stability, before, after)),
                StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("stability.txt"), stability.toText(),
                StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("causal-report.json"),
                EvalArtifactWriter.toJson(causal.toMap()), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("causal-report.txt"),
                causal.toText(), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("metadata.json"),
                EvalArtifactWriter.toJson(metadata(diagnosticRunId, instrumentationCommit,
                        before, after, scenarios, stamped)), StandardCharsets.UTF_8);

        System.out.println("Eval live diagnostic: " + outputDir.toAbsolutePath());
        System.out.println(causal.toText());
    }

    static List<ScenarioDefinition> targetedCorpus() {
        return List.of(
                EvalCorpus.e01(),
                EvalCorpus.e07(),
                EvalCorpus.e07Resolved(),
                EvalCorpus.e17(),
                EvalCorpusBatch2.e22Wait(),
                EvalCorpusBatch2.e19(),
                EvalCorpusBatch2.e10(),
                EvalCorpus.e25());
    }

    private static void assumeHealthMonotonic(LiveChainEvidence before,
                                              LiveBrainHealth after) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                after.stateUpdates() >= before.pythonBefore().stateUpdates()
                        && after.decisions() >= before.pythonBefore().decisions(),
                "agent-brain invocation counters reset during targeted diagnostic run");
    }

    private static Map<String, Object> stabilityMap(LiveStabilitySummary stability,
                                                     LiveChainEvidence before,
                                                     LiveBrainHealth after) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total_attempts", stability.totalAttempts());
        map.put("passed", stability.passed());
        map.put("failed", stability.failed());
        map.put("pass_rate", stability.passRate());
        map.put("layer_a_pass_rate", stability.layerAPassRate());
        map.put("stability", stability.stability());
        Map<String, Integer> failures = new LinkedHashMap<>();
        stability.failureCounts().forEach((key, value) -> failures.put(key.name(), value));
        map.put("failure_counts", failures);
        map.put("primary_action_distribution", new java.util.TreeMap<>(
                stability.primaryActionDistribution()));
        map.put("production_model_calls", stability.productionModelCalls());
        map.put("provider_retries", stability.providerRetries());
        map.put("capability_calls", stability.capabilityCalls());
        map.put("scenario_results", stability.scenarioResults());
        map.put("notes", stability.notes());
        map.put("provider_before", healthMap(before.pythonBefore()));
        map.put("provider_after", healthMap(after));
        return map;
    }

    private static Map<String, Object> metadata(String runId, String instrumentationCommit,
                                                 LiveChainEvidence before,
                                                 LiveBrainHealth after,
                                                 List<ScenarioDefinition> scenarios,
                                                 List<ObservationEnvelope> observations) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema_version", "eval-live-diagnostic.v1");
        map.put("diagnostic_run_id", runId);
        map.put("baseline_reference_commit", BASELINE_REFERENCE_COMMIT);
        map.put("instrumentation_commit", instrumentationCommit);
        map.put("prompt_changes", false);
        map.put("scenario_corpus_identity", corpusIdentity(scenarios));
        map.put("targeted_variants", scenarios.stream().flatMap(scenario ->
                scenario.variants().stream().map(variant ->
                        scenario.scenarioId() + "/" + variant.variantId())).toList());
        map.put("targeted_attempts", observations.size());
        map.put("repetitions_per_variant", DIAGNOSTIC_REPETITIONS);
        map.put("provider_model_provenance", Map.of(
                "java_engine", before.javaWiring().decisionEngine(),
                "java_inference_gateway", before.javaWiring().inferenceGateway(),
                "endpoint", before.endpoint(),
                "python_protocol", before.pythonBefore().protocolVersion(),
                "python_mode", before.pythonBefore().modelMode(),
                "model", before.selectedModel(),
                "credential_source", before.credentialSource()));
        map.put("provider_failures", observations.stream()
                .filter(observation -> observation.executionResult() != null
                        && observation.executionResult().startsWith("failed:"))
                .count());
        map.put("trace_completeness", traceCompleteness(observations));
        map.put("provider_after", healthMap(after));
        return map;
    }

    private static Map<String, Object> traceCompleteness(List<ObservationEnvelope> observations) {
        List<String> required = List.of("STATE_UPDATE_INPUT", "STATE_UPDATE_OUTPUT",
                "POST_STATE_UPDATE_STATE", "DECISION_INPUT", "DECISION_OUTPUT",
                "FINAL_RESULT");
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String stage : required) {
            counts.put(stage, observations.stream().filter(observation ->
                    observation.semanticTrace().stages().containsKey(stage)).count());
        }
        counts.put("total", observations.size());
        counts.put("semantic_trace_schema", SemanticTrace.SCHEMA_VERSION);
        return counts;
    }

    private static Map<String, Object> healthMap(LiveBrainHealth health) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("protocol", health.protocolVersion());
        map.put("model_mode", health.modelMode());
        map.put("state_updates", health.stateUpdates());
        map.put("decisions", health.decisions());
        return map;
    }

    private static String corpusIdentity(List<ScenarioDefinition> scenarios) {
        String canonical = scenarios.stream()
                .map(scenario -> scenario.scenarioId() + ":" + scenario.scenarioHash())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        return Hashes.sha256Hex(canonical);
    }

    private static String liveProvider() {
        return "opencode-zen";
    }

    private static String liveModelConfigDigest(LiveChainEvidence evidence) {
        return Hashes.sha256Hex(evidence.endpoint() + "\n"
                + evidence.selectedModel() + "\n" + evidence.credentialSource());
    }

    private static String readGitSha() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            process.waitFor();
            return output.isEmpty() ? "unknown" : output;
        } catch (Exception ex) {
            return "unknown";
        }
    }
}
