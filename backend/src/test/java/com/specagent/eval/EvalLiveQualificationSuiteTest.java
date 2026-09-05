package com.specagent.eval;

import com.specagent.common.Hashes;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider qualification only. This is a five-cycle protocol probe, not a
 * behavioral score used to select a model. The fixed E01/base setup exercises
 * the existing STATE_UPDATE -> DECISION production path; only completion,
 * schema/protocol evidence, retries, latency, and provider failures are used.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18084",
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
class EvalLiveQualificationSuiteTest extends EvalLiveHarnessBase {

    private static final int QUALIFICATION_CYCLES = 5;
    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @Test
    void qualifyConfiguredReferenceModel() throws Exception {
        String healthUrl = System.getenv().getOrDefault(
                "SPEC_AGENT_EVAL_BRAIN_BASE_URL", "http://localhost:8100") + "/health";
        LiveChainEvidence before = requireLiveBrain(healthUrl);

        ScenarioDefinition probeScenario = EvalCorpus.e01();
        VariantSpec probeVariant = probeScenario.variants().stream()
                .filter(variant -> "base".equals(variant.variantId()))
                .findFirst().orElseThrow();
        List<ObservationEnvelope> observations = new ArrayList<>(
                runLiveScenario(probeScenario, probeVariant, QUALIFICATION_CYCLES));
        String runId = java.util.UUID.randomUUID().toString();
        String gitSha = readGitSha();
        List<ObservationEnvelope> stamped = observations.stream()
                .map(observation -> observation.withRunMetadata(
                        runId, gitSha, "opencode-zen", before.selectedModel(),
                        liveModelConfigDigest(before)))
                .toList();

        LiveBrainHealth after = readLiveBrainHealth(healthUrl);
        assertThat(after.stateUpdates())
                .as("qualification STATE_UPDATE invocations")
                .isGreaterThanOrEqualTo(before.pythonBefore().stateUpdates() + QUALIFICATION_CYCLES);
        assertThat(after.decisions())
                .as("qualification DECISION invocations")
                .isGreaterThanOrEqualTo(before.pythonBefore().decisions() + QUALIFICATION_CYCLES);

        LiveStabilitySummary reliability = LiveStabilitySummary.from(
                stamped, QUALIFICATION_CYCLES, QUALIFICATION_CYCLES);
        long completedCycles = stamped.stream()
                .filter(EvalLiveQualificationSuiteTest::isCompletedCycle)
                .count();
        long schemaFailures = stamped.stream()
                .filter(EvalLiveQualificationSuiteTest::hasSchemaFailure)
                .count();
        long retryingCycles = stamped.stream()
                .filter(observation -> observation.providerRetries() > 0)
                .count();

        Path outputDir = Path.of(System.getProperty("user.dir"), "build",
                "eval-live-qualification",
                DIRECTORY_TIME.format(Instant.now()) + "-" + safeSegment(before.selectedModel()));
        Files.createDirectories(outputDir);
        String jsonl = stamped.stream().map(EvalArtifactWriter::toJsonl)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
        Files.writeString(outputDir.resolve("results.jsonl"), jsonl,
                StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("reliability.json"),
                EvalArtifactWriter.toJson(reliabilityMap(reliability, completedCycles,
                        schemaFailures, retryingCycles)), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("metadata.json"),
                EvalArtifactWriter.toJson(metadata(runId, gitSha, before, after,
                        stamped, completedCycles, schemaFailures, retryingCycles)),
                StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("reliability.txt"),
                reliability.toText(), StandardCharsets.UTF_8);

        System.out.println("Reference qualification: " + outputDir.toAbsolutePath());
        System.out.println(reliability.toText());

        // Qualification is intentionally stricter than behavioral baseline:
        // every protocol probe must complete without provider/schema failure.
        assertThat(completedCycles).as("completed protocol cycles").isEqualTo(QUALIFICATION_CYCLES);
        assertThat(reliability.infrastructureFailed()).as("provider failures").isZero();
        assertThat(schemaFailures).as("schema failures").isZero();
        assertThat(retryingCycles).as("cycles requiring provider retries").isZero();
    }

    private static boolean isCompletedCycle(ObservationEnvelope observation) {
        return !LiveFailureClassifier.isInfrastructureFailure(observation)
                && observation.actualPrimaryAction() != null
                && observation.semanticTrace().stages().containsKey("STATE_UPDATE_OUTPUT")
                && observation.semanticTrace().stages().containsKey("DECISION_OUTPUT");
    }

    private static boolean hasSchemaFailure(ObservationEnvelope observation) {
        return LiveFailureClassifier.isSchemaFailure(observation)
                || !observation.semanticTrace().stages().containsKey("STATE_UPDATE_OUTPUT")
                || !observation.semanticTrace().stages().containsKey("DECISION_OUTPUT");
    }

    private static Map<String, Object> reliabilityMap(LiveStabilitySummary summary,
                                                       long completedCycles,
                                                       long schemaFailures,
                                                       long retryingCycles) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planned_cycles", summary.plannedAttempts());
        map.put("executed_cycles", summary.totalAttempts());
        map.put("successful_cycles", completedCycles);
        map.put("provider_failures", summary.infrastructureFailed());
        map.put("provider_failure_classes", summary.providerFailureClasses());
        map.put("schema_failures", schemaFailures);
        map.put("cycles_with_retries", retryingCycles);
        map.put("availability_rate", summary.availabilityRate());
        map.put("latency_ms_total", summary.totalLatencyMs());
        map.put("latency_ms_average", summary.totalAttempts() == 0 ? 0.0
                : (double) summary.totalLatencyMs() / summary.totalAttempts());
        return map;
    }

    private static Map<String, Object> metadata(String runId, String gitSha,
                                                 LiveChainEvidence before,
                                                 LiveBrainHealth after,
                                                 List<ObservationEnvelope> observations,
                                                 long completedCycles,
                                                 long schemaFailures,
                                                 long retryingCycles) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema_version", "eval-live-qualification.v1");
        map.put("run_id", runId);
        map.put("git_sha", gitSha);
        map.put("qualification_date", Instant.now().toString());
        map.put("probe", "existing E01/base runtime setup; behavioral score excluded");
        map.put("cycles", QUALIFICATION_CYCLES);
        map.put("model", before.selectedModel());
        map.put("provider", "opencode-zen");
        map.put("endpoint", before.endpoint());
        map.put("user_agent", com.specagent.model.provider.OpenCodeZenTransport.USER_AGENT);
        map.put("credential_source", before.credentialSource());
        map.put("java_engine", before.javaWiring().decisionEngine());
        map.put("java_inference_gateway", before.javaWiring().inferenceGateway());
        map.put("python_protocol", before.pythonBefore().protocolVersion());
        map.put("python_mode", before.pythonBefore().modelMode());
        map.put("prompt_hashes", EvalArtifactWriter.promptProvenance(observations));
        map.put("successful_cycles", completedCycles);
        map.put("schema_failures", schemaFailures);
        map.put("cycles_with_retries", retryingCycles);
        map.put("provider_health_before", healthMap(before.pythonBefore()));
        map.put("provider_health_after", healthMap(after));
        return map;
    }

    private static Map<String, Object> healthMap(LiveBrainHealth health) {
        return Map.of("protocol", health.protocolVersion(),
                "model_mode", health.modelMode(),
                "state_updates", health.stateUpdates(),
                "decisions", health.decisions());
    }

    private static String liveModelConfigDigest(LiveChainEvidence evidence) {
        return Hashes.sha256Hex(evidence.endpoint() + "\n"
                + evidence.selectedModel() + "\n" + evidence.credentialSource());
    }

    private static String safeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown-model";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
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
