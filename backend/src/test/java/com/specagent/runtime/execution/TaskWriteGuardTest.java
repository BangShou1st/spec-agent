package com.specagent.runtime.execution;

import com.specagent.common.Ids;
import com.specagent.common.Maps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:TaskWriteGuardTest.java
 *
 * 测试目标:任务级写入护栏是"迟到写入拒绝"的唯一权威(design §6.3)。
 *
 * <p>用真实 PostgreSQL 的 runtime_tasks 行锁证明:
 * <ul>
 *   <li>token 三项(worker/epoch/lease)任一项不匹配即拒绝——这正是接管后
 *       旧执行器迟到写入必然落空的原因;</li>
 *   <li>租约过期后拒绝写入(过期租约不能靠"还没人被接管"继续写);</li>
 *   <li>取消后拒绝写入;</li>
 *   <li>epoch=0 的"无 token 放行"被执行路径删除(缺少 token 直接拒绝);</li>
 *   <li>匹配的 token 可以正常写入。</li>
 * </ul>
 *
 * <p>全部数据自建自清,只操作 runtime_* 表,不触碰用户业务库。
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskWriteGuardTest {

    @Autowired TaskWriteGuard guard;
    @Autowired RuntimeTaskRepository tasks;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final List<UUID> touched = new java.util.ArrayList<>();

    @BeforeEach
    void ensureSchema() {
        // 迁移已经把 runtime_tasks 建好;此处只做存在性断言,避免静默失效。
        Integer count = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'runtime_tasks'",
                Integer.class);
        assertThat(count).as("runtime_tasks must exist (V55 migration)").isEqualTo(1);
    }

    @AfterEach
    void cleanUp() {
        for (UUID taskId : touched) {
            jdbc.update("DELETE FROM runtime_task_attempts WHERE task_id = :id", Map.of("id", taskId));
            jdbc.update("DELETE FROM runtime_task_capacity WHERE task_id = :id", Map.of("id", taskId));
            jdbc.update("DELETE FROM runtime_scope_owners WHERE task_id = :id", Map.of("id", taskId));
            jdbc.update("DELETE FROM runtime_task_scopes WHERE task_id = :id", Map.of("id", taskId));
            jdbc.update("DELETE FROM runtime_tasks WHERE id = :id", Map.of("id", taskId));
        }
        touched.clear();
    }

    /**
     * 直接建一个 LEASED 任务(绕过调度器),用于逐项验证护栏的判定。
     */
    /** 建一个带真实业务 Run 的 LEASED 任务(满足外键与 CHECK 约束)。 */
    private record LeasedFixture(ExecutionToken token, UUID runId, UUID projectId) {
    }

    private LeasedFixture leasedFixture(long epoch, UUID workerId, UUID leaseId,
                                        Instant leaseUntil) {
        UUID projectId = Ids.random();
        UUID runId = Ids.random();
        jdbc.update("INSERT INTO projects (id, org_id, created_by, title, created_at, updated_at) "
                        + "VALUES (:id, '00000000-0000-4000-8000-000000000001', "
                        + "'00000000-0000-4000-8000-000000000002', 'task-guard', now(), now())",
                Map.of("id", projectId));
        jdbc.update("""
                INSERT INTO agent_runs (id, project_id, trigger_type, status, trace,
                    created_at, owner_epoch)
                VALUES (:id, :projectId, 'DECISION_CYCLE', 'created', NULL, now(), 0)
                """, Map.of("id", runId, "projectId", projectId));
        UUID taskId = Ids.random();
        touched.add(taskId);
        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, worker_id, execution_epoch, lease_id, lease_until, heartbeat_at,
                    attempt_count, max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', :id, 'WORKSPACE', :runId, NULL, 'DECISION_CYCLE',
                    'LEASED', :workerId, :epoch, :leaseId, :leaseUntil, now(),
                    1, 3, now(), now(), 1)
                """, Maps.of(
                "id", taskId,
                "runId", runId,
                "workerId", workerId,
                "epoch", epoch,
                "leaseId", leaseId,
                "leaseUntil", Timestamp.from(leaseUntil)));
        String scope = RuntimeExecutionProtocol.projectScope(projectId);
        jdbc.update("INSERT INTO runtime_task_scopes(task_id,scope_key) VALUES(:id,:scope)",
                Map.of("id",taskId,"scope",scope));
        jdbc.update("""
                INSERT INTO runtime_scope_owners(scope_key,task_id,execution_epoch,lease_id,worker_id)
                VALUES(:scope,:id,:epoch,:lease,:worker)
                """,Map.of("scope",scope,"id",taskId,"epoch",epoch,"lease",leaseId,"worker",workerId));
        return new LeasedFixture(new ExecutionToken(taskId, epoch, leaseId, workerId,
                RuntimeTaskFamily.WORKSPACE, runId), runId, projectId);
    }

    private void cleanUpFixture(LeasedFixture fixture) {
        jdbc.update("DELETE FROM agent_runs WHERE id = :id", Map.of("id", fixture.runId()));
        jdbc.update("DELETE FROM projects WHERE id = :id", Map.of("id", fixture.projectId()));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void matchingTokenIsAccepted() {
        UUID worker = Ids.random();
        UUID lease = Ids.random();
        LeasedFixture fixture = leasedFixture(1L, worker, lease, Instant.now().plusSeconds(60));
        try {
            ExecutionToken verified = guard.lockOwnedTask(fixture.token());
            assertThat(verified.epoch()).isEqualTo(1L);
            assertThat(guard.stillOwned(fixture.token())).isTrue();
        } finally {
            cleanUpFixture(fixture);
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void missingScopeOwnerRefusesEvenAMatchingTaskToken() {
        LeasedFixture fixture=leasedFixture(1L,Ids.random(),Ids.random(),Instant.now().plusSeconds(60));
        try {
            jdbc.update("DELETE FROM runtime_scope_owners WHERE task_id=:id",Map.of("id",fixture.token().taskId()));
            org.assertj.core.api.Assertions.assertThatThrownBy(()->guard.lockOwnedTask(fixture.token()))
                    .isInstanceOf(TaskOwnershipLostException.class).hasMessageContaining("binding is missing");
        } finally {cleanUpFixture(fixture);}
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void declaredScopeCannotSubstituteForAnotherOwnedScope() {
        LeasedFixture fixture=leasedFixture(1L,Ids.random(),Ids.random(),Instant.now().plusSeconds(60));
        try {
            jdbc.update("UPDATE runtime_task_scopes SET scope_key=:wrong WHERE task_id=:id",
                    Map.of("wrong",RuntimeExecutionProtocol.projectScope(Ids.random()),"id",fixture.token().taskId()));
            org.assertj.core.api.Assertions.assertThatThrownBy(()->guard.lockOwnedTask(fixture.token()))
                    .isInstanceOf(TaskOwnershipLostException.class).hasMessageContaining("binding is missing");
        } finally {cleanUpFixture(fixture);}
    }

    @Test
    void tokenOfAnOlderEpochIsRefusedAfterTakeover() {
        UUID worker = Ids.random();
        LeasedFixture fixture = leasedFixture(1L, worker, Ids.random(),
                Instant.now().plusSeconds(60));
        try {
            // 模拟接管:代次递增、新 lease、新 worker(旧 token 的一切条件都失效)
            jdbc.update("UPDATE runtime_tasks SET execution_epoch = 2, lease_id = :leaseId, "
                            + "worker_id = :newWorker WHERE id = :id",
                    Maps.of("id", fixture.token().taskId(), "leaseId", Ids.random(),
                            "newWorker", Ids.random()));

            assertThat(guard.stillOwned(fixture.token())).isFalse();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> guard.lockOwnedTask(fixture.token()))
                    .as("an old epoch must never write after takeover")
                    .isInstanceOf(TaskOwnershipLostException.class);
        } finally {
            cleanUpFixture(fixture);
        }
    }

    @Test
    void expiredLeaseRefusesWritesEvenWithoutTakeover() {
        UUID worker = Ids.random();
        LeasedFixture fixture = leasedFixture(1L, worker, Ids.random(),
                Instant.now().minusSeconds(5));
        try {
            // 尚无任何接管者、epoch 未变——过期租约本身就不允许继续写入。
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> guard.lockOwnedTask(fixture.token()))
                    .as("an expired lease must not be revivable by writing")
                    .isInstanceOf(TaskOwnershipLostException.class);
        } finally {
            cleanUpFixture(fixture);
        }
    }

    @Test
    void cancelledTaskRefusesWrites() {
        UUID worker = Ids.random();
        LeasedFixture fixture = leasedFixture(1L, worker, Ids.random(),
                Instant.now().plusSeconds(60));
        try {
            jdbc.update("UPDATE runtime_tasks SET cancel_requested_at = now() WHERE id = :id",
                    Map.of("id", fixture.token().taskId()));
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> guard.lockOwnedTask(fixture.token()))
                    .as("a cancelled task must refuse late writes")
                    .isInstanceOf(TaskOwnershipLostException.class);
        } finally {
            cleanUpFixture(fixture);
        }
    }

    @Test
    void expiredAbsoluteDeadlineRefusesWrites() {
        UUID worker = Ids.random();
        LeasedFixture fixture = leasedFixture(1L, worker, Ids.random(),
                Instant.now().plusSeconds(60));
        try {
            // deadline 已过而租约仍在:绝对总截止时间优先于租约续期。
            jdbc.update("UPDATE runtime_tasks SET deadline_at = now() - interval '1 second' "
                    + "WHERE id = :id", Map.of("id", fixture.token().taskId()));
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> guard.lockOwnedTask(fixture.token()))
                    .as("the absolute deadline must not be resettable by renewal")
                    .isInstanceOf(TaskOwnershipLostException.class);
        } finally {
            cleanUpFixture(fixture);
        }
    }

    @Test
    void nonLeasedTaskRefusesWrites() {
        UUID worker = Ids.random();
        LeasedFixture fixture = leasedFixture(1L, worker, Ids.random(),
                Instant.now().plusSeconds(60));
        try {
            jdbc.update("UPDATE runtime_tasks SET state = 'QUEUED' WHERE id = :id",
                    Map.of("id", fixture.token().taskId()));
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> guard.lockOwnedTask(fixture.token()))
                    .as("only LEASED tasks accept protected writes")
                    .isInstanceOf(TaskOwnershipLostException.class);
        } finally {
            cleanUpFixture(fixture);
        }
    }

    @Test
    void leaseRenewalExtendsLeaseButNeverTheDeadline() {
        UUID worker = Ids.random();
        LeasedFixture fixture = leasedFixture(1L, worker, Ids.random(),
                Instant.now().plusSeconds(60));
        try {
            jdbc.update("UPDATE runtime_tasks SET deadline_at = now() + interval '120 seconds' "
                    + "WHERE id = :id", Map.of("id", fixture.token().taskId()));
            Timestamp deadlineBefore = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT deadline_at FROM runtime_tasks WHERE id = ?", Timestamp.class,
                    fixture.token().taskId());

            assertThat(tasks.renewLease(fixture.token(), 120)).isEqualTo(1);

            Timestamp deadlineAfter = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT deadline_at FROM runtime_tasks WHERE id = ?", Timestamp.class,
                    fixture.token().taskId());
            assertThat(deadlineAfter)
                    .as("renewal must never extend the absolute deadline")
                    .isEqualTo(deadlineBefore);
            Boolean extended = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT lease_until > now() + interval '100 seconds' FROM runtime_tasks "
                            + "WHERE id = ?", Boolean.class, fixture.token().taskId());
            assertThat(extended).as("renewal extends the lease itself").isTrue();
        } finally {
            cleanUpFixture(fixture);
        }
    }

    @Test
    void expiredLeaseCannotBeRenewed() {
        UUID worker = Ids.random();
        LeasedFixture fixture = leasedFixture(1L, worker, Ids.random(),
                Instant.now().minusSeconds(5));
        try {
            assertThat(tasks.renewLease(fixture.token(), 60))
                    .as("an already-expired lease must not be revived by renewal")
                    .isZero();
        } finally {
            cleanUpFixture(fixture);
        }
    }
}
