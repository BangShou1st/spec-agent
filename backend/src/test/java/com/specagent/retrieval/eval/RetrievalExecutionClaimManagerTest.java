package com.specagent.retrieval.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalExecutionClaimManagerTest {

    @Test
    @DisplayName("首次验收认领成功生成唯一 runId 与 CLAIMED 初始状态")
    void firstClaimSucceedsAndGeneratesRunId(@TempDir Path tempDir) {
        String datasetSha = "fake_sha_" + UUID.randomUUID();
        String freezeSha = "freeze_sha_123";

        var claim = RetrievalExecutionClaimManager.claimFirstRun(datasetSha, freezeSha, tempDir);

        assertThat(claim.runId()).isNotBlank();
        assertThat(claim.datasetSha256()).isEqualTo(datasetSha);
        assertThat(claim.freezeSha256()).isEqualTo(freezeSha);
        assertThat(claim.status()).isEqualTo("CLAIMED");

        // 验证文件存在
        assertThat(tempDir.resolve(datasetSha + ".claim.json")).exists();
    }

    @Test
    @DisplayName("同一数据集二次认领必须抛出 ClaimCollisionException 拒绝重复执行")
    void subsequentClaimOnSameDatasetFails(@TempDir Path tempDir) {
        String datasetSha = "fake_sha_repeat";
        String freezeSha = "freeze_sha_repeat";

        RetrievalExecutionClaimManager.claimFirstRun(datasetSha, freezeSha, tempDir);

        assertThatThrownBy(() -> RetrievalExecutionClaimManager.claimFirstRun(datasetSha, freezeSha, tempDir))
                .isInstanceOf(RetrievalExecutionClaimManager.ClaimCollisionException.class)
                .hasMessageContaining("has already been claimed");
    }

    @Test
    @DisplayName("执行失败或中断后记录保留，后续再次认领仍然拒绝，不可恢复为 NOT_RUN")
    void failedOrInterruptedOutcomeIsRecordedAndPreventsRerun(@TempDir Path tempDir) {
        String datasetSha = "fake_sha_fail";
        String freezeSha = "freeze_sha_fail";

        var claim = RetrievalExecutionClaimManager.claimFirstRun(datasetSha, freezeSha, tempDir);

        // 记录失败结果
        var updated = RetrievalExecutionClaimManager.recordOutcome(
                claim,
                "COMPLETED_FAIL",
                "Quality gate failed: Recall 0.80 < 0.85",
                tempDir
        );
        assertThat(updated.status()).isEqualTo("COMPLETED_FAIL");

        // 尝试再次认领应坚决拒绝，保护首次执行历史事实
        assertThatThrownBy(() -> RetrievalExecutionClaimManager.claimFirstRun(datasetSha, freezeSha, tempDir))
                .isInstanceOf(RetrievalExecutionClaimManager.ClaimCollisionException.class)
                .hasMessageContaining("already been claimed");
    }

    @Test
    @DisplayName("终态不可被任何后续状态覆盖：COMPLETED_FAIL 绝不允许覆写为 COMPLETED_PASS")
    void terminalStateCannotBeOverwritten(@TempDir Path tempDir) {
        String datasetSha = "fake_sha_terminal";
        String freezeSha = "freeze_sha_terminal";

        var claim = RetrievalExecutionClaimManager.claimFirstRun(datasetSha, freezeSha, tempDir);

        // 首次记录终态失败
        RetrievalExecutionClaimManager.recordOutcome(
                claim,
                "COMPLETED_FAIL",
                "Recall 0.70 < 0.85",
                tempDir
        );

        // 试图再次覆写为成功，必须被严正拒绝
        assertThatThrownBy(() -> RetrievalExecutionClaimManager.recordOutcome(
                claim,
                "COMPLETED_PASS",
                "Forged pass attempt",
                tempDir
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot be overwritten");

        // 独立 runId 回执文件必须存在
        assertThat(tempDir.resolve(datasetSha + "." + claim.runId() + ".receipt.json")).exists();
    }

    @Test
    @DisplayName("并发认领争抢同一数据集，仅允许一个胜出，其余必须抛出 ClaimCollisionException")
    void concurrentClaimsOnlyOneWins(@TempDir Path tempDir) throws Exception {
        String datasetSha = "fake_sha_concurrent";
        String freezeSha = "freeze_sha_concurrent";
        int threads = 8;
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var barrier = new java.util.concurrent.CyclicBarrier(threads);
        var successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        var collisionCount = new java.util.concurrent.atomic.AtomicInteger(0);

        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    RetrievalExecutionClaimManager.claimFirstRun(datasetSha, freezeSha, tempDir);
                    successCount.incrementAndGet();
                } catch (RetrievalExecutionClaimManager.ClaimCollisionException e) {
                    collisionCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (var f : futures) f.get();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(collisionCount.get()).isEqualTo(threads - 1);
    }

    @Test void changedDatasetAndFreezeCannotRenewFamily(@TempDir Path directory) {
        RetrievalExecutionClaimManager.claimFirstRun("family", "dataset_one", "freeze_one", directory);
        assertThatThrownBy(() -> RetrievalExecutionClaimManager.claimFirstRun("family", "dataset_two", "freeze_two", directory))
                .isInstanceOf(RetrievalExecutionClaimManager.ClaimCollisionException.class);
    }

    @Test void concurrentOutcomesAndResultsAreAppendOnly(@TempDir Path directory) throws Exception {
        var claim = RetrievalExecutionClaimManager.claimFirstRun("dataset", "freeze", directory);
        byte[] initial = java.nio.file.Files.readAllBytes(directory.resolve("dataset.claim.json"));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var ready = new java.util.concurrent.CyclicBarrier(8);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                ready.await();
                try { RetrievalExecutionClaimManager.recordOutcome(claim,"COMPLETED_FAIL","failure",directory); return true; }
                catch (IllegalStateException collision) { return false; }
            }));
            int wins = 0;
            for (var future : futures) if (future.get()) wins++;
            assertThat(wins).isEqualTo(1);
        }
        assertThat(java.nio.file.Files.readAllBytes(directory.resolve("dataset.claim.json"))).isEqualTo(initial);
        RetrievalExecutionClaimManager.writeResult(claim,"{\"verdict\":\"FAIL\"}",directory);
        assertThatThrownBy(() -> RetrievalExecutionClaimManager.writeResult(claim,"PASS",directory)).isInstanceOf(java.io.UncheckedIOException.class);
        assertThatThrownBy(() -> RetrievalExecutionClaimManager.recordOutcome(claim,"CLAIMED","reset",directory)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void partialClaimAfterProcessDeathStillConsumesRun(@TempDir Path directory) throws Exception {
        java.nio.file.Files.writeString(directory.resolve("family.claim.json"),"{partial");
        assertThatThrownBy(() -> RetrievalExecutionClaimManager.claimFirstRun("family","dataset","freeze",directory))
            .isInstanceOf(RetrievalExecutionClaimManager.ClaimCollisionException.class);
    }

    @Test void legacyHashedCandidateClaimAlsoPreventsFreshFamily(@TempDir Path directory) throws Exception {
        java.nio.file.Files.writeString(directory.resolve("old_dataset_hash.claim.json"),"{partial");
        assertThatThrownBy(() -> RetrievalExecutionClaimManager.claimFirstRun("rag-d3-v2-68","new_dataset","new_freeze",directory))
            .isInstanceOf(RetrievalExecutionClaimManager.ClaimCollisionException.class);
    }

    @Test void separateProcessesCannotDoubleClaim(@TempDir Path directory) throws Exception {
        var processes = new java.util.ArrayList<Process>();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        for (int i=0;i<4;i++) processes.add(new ProcessBuilder(javaExecutable,"-cp",System.getProperty("java.class.path"),
                getClass().getName(),directory.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
        int wins=0;
        for(var process:processes) {
            assertThat(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isIn(0,2);
            if(process.exitValue()==0) wins++;
        }
        assertThat(wins).isEqualTo(1);
    }

    public static void main(String[] args) {
        try { RetrievalExecutionClaimManager.claimFirstRun("family","dataset","freeze",Path.of(args[0])); }
        catch (RetrievalExecutionClaimManager.ClaimCollisionException collision) { System.exit(2); }
    }
}
