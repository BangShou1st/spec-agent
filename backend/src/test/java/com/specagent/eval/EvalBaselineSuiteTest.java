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
 * 文件名:EvalBaselineSuiteTest.java
 *
 * 测试目标:确定性基线套件(阻塞 CI,B-fast 档)。用脚本化 B-fast 大脑把
 * 整个语料跑过生产回答循环,并写出机器可读产物:{@code results.jsonl}、
 * {@code summary.json}、{@code summary.txt}。输出到 {@code build/eval-baseline}
 * (绝不提交)。
 *
 * 验收类场景(E17 confirmed/stale、E25 stale)由各自的语料测试覆盖;
 * 本套件将运行器产生的全部尝试(含 unconfirmed/pending 结果)记录为冻结基线。
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
        // 基线冻结:记录真实结果,不在此调整提示词。基线失败即套件失败,
        // 让回归直接阻塞 CI。
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
