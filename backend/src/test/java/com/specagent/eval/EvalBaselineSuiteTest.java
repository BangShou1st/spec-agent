package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic baseline suite (CI-blocking, B-fast profile).
 *
 * <p>Runs the whole corpus through the production answer cycle with the
 * scripted B-fast brain and writes machine-readable artifacts:
 * {@code results.jsonl}, {@code summary.json}, {@code summary.txt}.
 * Output goes to {@code build/eval-baseline} (never committed).
 *
 * <p>Acceptance scenarios (E17 confirmed/stale, E25 stale) are covered
 * by their dedicated corpus tests; this suite records the runner
 * attempts (unconfirmed/pending outcomes included) as the frozen
 * baseline.
 */
class EvalBaselineSuiteTest extends EvalHarnessBase {

    @Test
    void runFullCorpusAndWriteBaselineArtifacts() throws Exception {
        List<ObservationEnvelope> observations = new ArrayList<>();
        for (ScenarioDefinition scenario : EvalCorpus.all()) {
            for (VariantSpec variant : scenario.variants()) {
                observations.add(runScenario(scenario, variant));
            }
        }

        String runId = UUID.randomUUID().toString();
        String gitSha = readGitSha();
        List<ObservationEnvelope> stamped = new ArrayList<>();
        for (ObservationEnvelope observation : observations) {
            stamped.add(observation.withRunMetadata(
                    runId, gitSha, "fake", "scripted-brain", "scripted"));
        }

        Path outputDir = Path.of(System.getProperty("user.dir"), "build", "eval-baseline");
        Files.createDirectories(outputDir);
        StringBuilder jsonl = new StringBuilder();
        for (ObservationEnvelope observation : stamped) {
            jsonl.append(EvalArtifactWriter.toJsonl(observation)).append("\n");
        }
        Files.writeString(outputDir.resolve("results.jsonl"), jsonl.toString(),
                StandardCharsets.UTF_8);

        EvalSummary summary = EvalSummary.from(stamped);
        Files.writeString(outputDir.resolve("summary.json"),
                EvalArtifactWriter.summaryJson(summary), StandardCharsets.UTF_8);
        String header = "# eval baseline " + Instant.now() + " run=" + runId
                + " git=" + gitSha + "\n";
        Files.writeString(outputDir.resolve("summary.txt"), header + summary.toText(),
                StandardCharsets.UTF_8);

        System.out.println("Eval baseline: " + outputDir.toAbsolutePath());
        System.out.println(summary.toText());

        assertThat(summary.totalAttempts()).isEqualTo(countAttempts());
        // Baseline freeze: record reality, do not tune prompts here. A
        // failing baseline fails the suite so regressions block CI.
        assertThat(summary.failed())
                .as("baseline failures: %s", summary.toText())
                .isZero();
    }

    private static int countAttempts() {
        int attempts = 0;
        for (ScenarioDefinition scenario : EvalCorpus.all()) {
            attempts += scenario.variants().size();
        }
        return attempts;
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
