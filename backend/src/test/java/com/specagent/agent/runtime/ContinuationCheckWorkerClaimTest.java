package com.specagent.agent.runtime;

import com.specagent.common.Ids;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:ContinuationCheckWorkerClaimTest.java
 *
 * 测试目标:续跑检查的 worker 归属(design §10"后台检查使用 SKIP LOCKED + 幂等,
 * 避免双重续跑")。
 *
 * <p>改造前 {@code findPending} 是普通 SELECT:多 Worker 下两个实例会同时取到同一批
 * 待处理检查并各自评估。本测试用两个独立的 worker 身份证明:
 *
 * <ol>
 *   <li>同一批检查<b>不会被两个 Worker 同时认领</b>(SKIP LOCKED + 认领列);</li>
 *   <li>认领在<b>处理完成后</b>不会让检查被重复评估;</li>
 *   <li>瞬时失败会<b>立即释放认领</b>,不需等待认领过期;</li>
 *   <li>认领者崩溃(认领过期)后,其他 Worker 可以接管——一个死掉的 worker
 *       绝不能永久搁置一条待处理检查。</li>
 * </ol>
 *
 * <p>全部数据自建自清。
 */
@SpringBootTest
@ActiveProfiles("test")
class ContinuationCheckWorkerClaimTest {

    @Autowired ContinuationCheckRepository checks;
    @Autowired ProjectService projects;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final List<UUID> touchedProjects = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID projectId : touchedProjects) {
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("UPDATE agent_runs SET parent_run_id = NULL, root_run_id = NULL "
                    + "WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_runs WHERE project_id = :p", Map.of("p", projectId));
        }
        touchedProjects.clear();
    }

    /** 建一个终态 run 并登记一条续跑检查。 */
    private UUID terminalRunWithCheck() {
        Project project = projects.createProject("continuation claim " + UUID.randomUUID());
        touchedProjects.add(project.id());
        UUID runId = Ids.random();
        jdbc.update("""
                INSERT INTO agent_runs (id, project_id, trigger_type, status, trace,
                    created_at, completed_at, owner_epoch)
                VALUES (:id, :projectId, 'DECISION_CYCLE', 'completed', NULL, now(), now(), 0)
                """, Map.of("id", runId, "projectId", project.id()));
        checks.request(runId);
        return runId;
    }

    private UUID claimedBy(UUID runId) {
        return jdbc.queryForObject(
                "SELECT claimed_by FROM agent_run_continuation_checks WHERE run_id = :runId",
                Map.of("runId", runId), UUID.class);
    }

    @Test
    void twoWorkersNeverClaimTheSameCheck() {
        UUID runId = terminalRunWithCheck();
        UUID workerA = UUID.randomUUID();
        UUID workerB = UUID.randomUUID();

        var claimedByA = checks.claimPending(10, workerA, 60);
        var claimedByB = checks.claimPending(10, workerB, 60);

        assertThat(claimedByA).extracting(ContinuationCheck::runId).containsExactly(runId);
        assertThat(claimedByB)
                .as("a check already claimed by another live worker must not be handed out again")
                .isEmpty();
        assertThat(claimedBy(runId)).isEqualTo(workerA);
    }

    @Test
    void theSameWorkerReclaimsItsOwnCheckAcrossPolls() {
        UUID runId = terminalRunWithCheck();
        UUID workerA = UUID.randomUUID();

        assertThat(checks.claimPending(10, workerA, 60)).hasSize(1);
        // 同一个 worker 再次轮询自己的检查是合法的(幂等评估),不会被 SKIP LOCKED
        // 永久挡住。
        assertThat(checks.claimPending(10, workerA, 60))
                .as("the owning worker must be able to re-read its own claim")
                .extracting(ContinuationCheck::runId).containsExactly(runId);
    }

    @Test
    void releasedClaimBecomesImmediatelyAvailableToAnotherWorker() {
        UUID runId = terminalRunWithCheck();
        UUID workerA = UUID.randomUUID();
        UUID workerB = UUID.randomUUID();

        assertThat(checks.claimPending(10, workerA, 60)).hasSize(1);
        assertThat(workerB).isNotNull();
        assertThat(checks.claimPending(10, workerB, 60)).isEmpty();

        // 瞬时失败:释放认领,另一个 Worker 立刻可以接手。
        assertThat(checks.releaseClaim(runId, workerA)).isEqualTo(1);

        assertThat(checks.claimPending(10, workerB, 60))
                .as("a released claim must be immediately available")
                .extracting(ContinuationCheck::runId).containsExactly(runId);
        assertThat(claimedBy(runId)).isEqualTo(workerB);
    }

    @Test
    void aClaimThatWentStaleIsTakenOverByAnotherWorker() {
        UUID runId = terminalRunWithCheck();
        UUID workerA = UUID.randomUUID();
        UUID workerB = UUID.randomUUID();

        assertThat(checks.claimPending(10, workerA, 60)).hasSize(1);

        // 认领者崩溃:认领过期。其他 Worker 必须能接管,否则一条检查会被一个
        // 死掉的进程永久搁置。
        jdbc.update("UPDATE agent_run_continuation_checks "
                + "SET claim_expires_at = now() - interval '1 second' WHERE run_id = :runId",
                Map.of("runId", runId));

        assertThat(checks.claimPending(10, workerB, 60))
                .as("an expired claim must be recoverable by another worker")
                .extracting(ContinuationCheck::runId).containsExactly(runId);
        assertThat(claimedBy(runId)).isEqualTo(workerB);
    }

    @Test
    void processedChecksAreNeverClaimedAgain() {
        UUID runId = terminalRunWithCheck();
        UUID workerA = UUID.randomUUID();

        var claimed = checks.claimPending(10, workerA, 60);
        assertThat(claimed).hasSize(1);
        ContinuationCheck check = claimed.get(0);

        assertThat(checks.markProcessed(check.runId(), check.generation())).isEqualTo(1);
        assertThat(checks.claimPending(10, workerA, 60))
                .as("a processed check must never be handed out again")
                .isEmpty();
        assertThat(checks.claimPending(10, UUID.randomUUID(), 60)).isEmpty();
    }

    @Test
    void nullWorkerKeepsTheHistoricalSingleExecutorQuery() {
        UUID runId = terminalRunWithCheck();

        // 单执行器模式(workerId = null)不写认领列:行为与历史一致,
        // 不会因为多 Worker 的列而改变旧部署的可观测状态。
        assertThat(checks.claimPending(10, null, 0))
                .extracting(ContinuationCheck::runId).containsExactly(runId);
        assertThat(claimedBy(runId))
                .as("single-executor mode must not write worker ownership")
                .isNull();
    }
}