package com.specagent.runtime.execution;

import com.specagent.agent.runtime.SqlAnswerChainFacts;
import com.specagent.agent.runtime.WorkspaceAnswerChainRecoveryAdapter;
import com.specagent.agent.runtime.WorkspaceRunSteps;
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
 * 文件名:AnswerChainRecoveryAdapterTest.java
 *
 * 测试目标:P1-C 的安全接管判定(design §7、§12)。这是第一阶段"不能只完成
 * 多 Worker 就称恢复完成"的核心证明。
 *
 * <p>逐条验证核验规则:
 * <ol>
 *   <li>无记录步骤(尚未执行)→ 可接管;</li>
 *   <li>Answer 已提交且可验证 → 可接管(复用,不创建第二个);</li>
 *   <li>Patch 已提交且可验证 → 可接管(复用 checkpoint);</li>
 *   <li>结果引用悬空 → <b>拒绝</b>接管;</li>
 *   <li>不可重放步骤结果为 UNKNOWN → <b>拒绝</b>接管并提供核对记录;</li>
 *   <li>不可重放步骤仍 IN_FLIGHT → <b>拒绝</b>接管;</li>
 *   <li>patch 指向缺失的 answer → <b>拒绝</b>接管。</li>
 * </ol>
 *
 * <p>适配器只读:测试同时断言它不改变任何业务状态。
 */
@SpringBootTest
@ActiveProfiles("test")
class AnswerChainRecoveryAdapterTest {

    @Autowired WorkspaceAnswerChainRecoveryAdapter adapter;
    @Autowired WorkspaceRunStepRepository steps;
    @Autowired SqlAnswerChainFacts facts;
    @Autowired ProjectService projects;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final List<UUID> touchedProjects = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID projectId : touchedProjects) {
            jdbc.update("DELETE FROM workspace_run_steps WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_task_attempts WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p))", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_task_capacity WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p))", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_scope_owners WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p))", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_task_scopes WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p))", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            // 外键顺序:先摘掉 agent_runs 对产物/节点/路线的引用,再删产物,
            // 最后删 Run 与图事实。
            jdbc.update("UPDATE agent_runs SET produced_answer_id = NULL, produced_patch_id = NULL, "
                    + "input_node_id = NULL, route_id = NULL WHERE project_id = :p",
                    Map.of("p", projectId));
            jdbc.update("UPDATE projects SET active_route_id = NULL WHERE id = :p",
                    Map.of("p", projectId));
            jdbc.update("UPDATE routes SET root_node_id = NULL, tip_node_id = NULL "
                    + "WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM answer_patches WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM answers WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_runs WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM nodes WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM routes WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM projects WHERE id = :p", Map.of("p", projectId));
        }
        touchedProjects.clear();
    }

    /** 建一个真实的项目/路线/节点/Run 组合(满足全部外键)。 */
    private record Fixture(UUID projectId, UUID routeId, UUID nodeId, UUID runId, UUID taskId) {
    }

    private Fixture fixture() {
        Project project = projects.createProject("recovery-adapter " + UUID.randomUUID());
        touchedProjects.add(project.id());
        UUID routeId = project.activeRouteId();
        UUID nodeId = Ids.random();
        jdbc.update("""
                INSERT INTO nodes (id, project_id, question, created_at)
                VALUES (:id, :p, 'recovery fixture question', now())
                """, Map.of("id", nodeId, "p", project.id()));
        UUID runId = Ids.random();
        jdbc.update("""
                INSERT INTO agent_runs (id, project_id, route_id, trigger_type, input_node_id,
                    status, trace, operation, created_at, owner_epoch)
                VALUES (:id, :p, :routeId, 'ANSWER_CYCLE', :nodeId,
                    'running', NULL, 'SUBMIT_ANSWER', now(), 1)
                """, Map.of("id", runId, "p", project.id(), "routeId", routeId, "nodeId", nodeId));
        UUID taskId = Ids.random();
        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, worker_id, execution_epoch, lease_id, lease_until, attempt_count,
                    max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', :id, 'WORKSPACE', :runId, NULL, 'ANSWER_CYCLE',
                    'RECOVERING', NULL, 2, NULL, NULL, 1, 3, now(), now(), 1)
                """, Map.of("id", taskId, "runId", runId));
        return new Fixture(project.id(), routeId, nodeId, runId, taskId);
    }

    private RuntimeTask taskOf(Fixture fixture) {
        return new RuntimeTaskRepository(jdbc).findById(fixture.taskId()).orElseThrow();
    }

    private UUID insertAnswer(Fixture fixture) {
        UUID answerId = Ids.random();
        jdbc.update("""
                INSERT INTO answers (id, project_id, route_id, node_id, free_text, created_by_user, created_at)
                VALUES (:id, :p, :routeId, :nodeId, 'recovery answer', 'user', now())
                """, Map.of("id", answerId, "p", fixture.projectId(),
                "routeId", fixture.routeId(), "nodeId", fixture.nodeId()));
        jdbc.update("UPDATE agent_runs SET produced_answer_id = :answerId WHERE id = :runId",
                Map.of("answerId", answerId, "runId", fixture.runId()));
        return answerId;
    }

    private UUID insertPatch(Fixture fixture, UUID answerId) {
        UUID patchId = Ids.random();
        jdbc.update("""
                INSERT INTO answer_patches (id, project_id, route_id, source_node_id,
                    source_answer_id, claims, created_by_run_id, created_at)
                VALUES (:id, :p, :routeId, :nodeId, :answerId, '[]'::jsonb, :runId, now())
                """, Map.of("id", patchId, "p", fixture.projectId(), "routeId", fixture.routeId(),
                "nodeId", fixture.nodeId(), "answerId", answerId, "runId", fixture.runId()));
        jdbc.update("UPDATE agent_runs SET produced_patch_id = :patchId WHERE id = :runId",
                Map.of("patchId", patchId, "runId", fixture.runId()));
        return patchId;
    }

    @Test
    void noRecordedStepsIsSafeToTakeOver() {
        Fixture fixture = fixture();
        RecoveryVerdict verdict = adapter.assess(taskOf(fixture));
        assertThat(verdict.safe())
                .as("a task that has not started any step is always safe to take over")
                .isTrue();
    }

    @Test
    void persistedAnswerAndPatchAreSafeAndReused() {
        Fixture fixture = fixture();
        UUID answerId = insertAnswer(fixture);
        UUID patchId = insertPatch(fixture, answerId);
        steps.prepare(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED, "h-answer", 1,
                WorkspaceRunStepRetryClass.SAFE);
        steps.markSucceeded(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED, 1,
                WorkspaceRunSteps.answerRef(answerId));
        steps.prepare(fixture.runId(), WorkspaceRunSteps.PATCH_PERSISTED, "h-patch", 1,
                WorkspaceRunStepRetryClass.SAFE);
        steps.markSucceeded(fixture.runId(), WorkspaceRunSteps.PATCH_PERSISTED, 1,
                WorkspaceRunSteps.patchRef(patchId));

        RecoveryVerdict verdict = adapter.assess(taskOf(fixture));

        assertThat(verdict.safe())
                .as("a verified answer+patch chain is safe to take over and reuse")
                .isTrue();
        // 复用前提:答案与补丁各恰好一个,接管绝不会产生第二个。
        assertThat(facts.chainShape(fixture.runId()).answerCount()).isEqualTo(1);
        assertThat(facts.chainShape(fixture.runId()).patchCount()).isEqualTo(1);
    }

    @Test
    void danglingResultReferenceRefusesTakeover() {
        Fixture fixture = fixture();
        // 标记 SUCCEEDED 但引用一个不存在的 Answer。
        steps.prepare(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED, "h", 1,
                WorkspaceRunStepRetryClass.SAFE);
        steps.markSucceeded(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED, 1,
                WorkspaceRunSteps.answerRef(Ids.random()));

        RecoveryVerdict verdict = adapter.assess(taskOf(fixture));

        assertThat(verdict.safe())
                .as("a SUCCEEDED step whose result row is missing must not be trusted")
                .isFalse();
        assertThat(verdict.reason()).contains("cannot be verified");
        assertThat(verdict.reconciliationRecord()).isNotBlank();
    }

    @Test
    void unknownOutcomeOfAnUnsafeStepRefusesTakeoverWithReconciliationRecord() {
        Fixture fixture = fixture();
        steps.prepare(fixture.runId(), WorkspaceRunSteps.STATE_UPDATE_MODEL, "h", 1,
                WorkspaceRunStepRetryClass.UNSAFE);
        steps.markUnknown(fixture.runId(), WorkspaceRunSteps.STATE_UPDATE_MODEL, 1, null);

        RecoveryVerdict verdict = adapter.assess(taskOf(fixture));

        assertThat(verdict.safe())
                .as("an UNKNOWN non-replayable step must stop recovery, never replay it")
                .isFalse();
        assertThat(verdict.reason()).contains("UNKNOWN");
        assertThat(verdict.reconciliationRecord())
                .as("the operator needs a reconciliation record")
                .contains(WorkspaceRunSteps.STATE_UPDATE_MODEL);
    }

    @Test
    void inFlightUnsafeStepRefusesTakeover() {
        Fixture fixture = fixture();
        steps.prepare(fixture.runId(), WorkspaceRunSteps.DECISION_MODEL, "h", 1,
                WorkspaceRunStepRetryClass.UNSAFE);
        steps.markInFlight(fixture.runId(), WorkspaceRunSteps.DECISION_MODEL, 1);

        RecoveryVerdict verdict = adapter.assess(taskOf(fixture));

        assertThat(verdict.safe())
                .as("an IN_FLIGHT external call may already have happened; do not replay it")
                .isFalse();
        assertThat(verdict.reason()).contains("IN_FLIGHT");
    }

    @Test
    void duplicatePatchCheckpointsRefuseTakeover() {
        Fixture fixture = fixture();
        // 为同一 Run 构造两个 checkpoint(第二个 answer 挂在另一个节点上,
        // 因此不受 answers 的 (route,node) 唯一约束阻挡)。接管若继续,
        // 就可能写出第三个 / 或基于错误的 checkpoint 继续。
        UUID firstAnswer = insertAnswer(fixture);
        insertPatch(fixture, firstAnswer);

        UUID secondNode = Ids.random();
        jdbc.update("""
                INSERT INTO nodes (id, project_id, question, created_at)
                VALUES (:id, :p, 'second recovery node', now())
                """, Map.of("id", secondNode, "p", fixture.projectId()));
        UUID secondAnswer = Ids.random();
        jdbc.update("""
                INSERT INTO answers (id, project_id, route_id, node_id, free_text, created_by_user, created_at)
                VALUES (:id, :p, :routeId, :nodeId, 'second answer', 'user', now())
                """, Map.of("id", secondAnswer, "p", fixture.projectId(),
                "routeId", fixture.routeId(), "nodeId", secondNode));
        UUID secondPatch = Ids.random();
        jdbc.update("""
                INSERT INTO answer_patches (id, project_id, route_id, source_node_id,
                    source_answer_id, claims, created_by_run_id, created_at)
                VALUES (:id, :p, :routeId, :nodeId, :answerId, '[]'::jsonb, :runId, now())
                """, Map.of("id", secondPatch, "p", fixture.projectId(), "routeId", fixture.routeId(),
                "nodeId", secondNode, "answerId", secondAnswer, "runId", fixture.runId()));

        RecoveryVerdict verdict = adapter.assess(taskOf(fixture));

        assertThat(verdict.safe())
                .as("more than one checkpoint for one run must stop automatic takeover")
                .isFalse();
        assertThat(verdict.reason()).contains("patch");
    }

    @Test
    void adapterIsReadOnly() {
        Fixture fixture = fixture();
        UUID answerId = insertAnswer(fixture);
        steps.prepare(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED, "h", 1,
                WorkspaceRunStepRetryClass.SAFE);
        steps.markSucceeded(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED, 1,
                WorkspaceRunSteps.answerRef(answerId));

        long answersBefore = count("SELECT COUNT(*) FROM answers WHERE project_id = :p",
                Map.of("p", fixture.projectId()));
        long stepsBefore = count("SELECT COUNT(*) FROM workspace_run_steps WHERE run_id = :r",
                Map.of("r", fixture.runId()));
        String runStatusBefore = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = :r", Map.of("r", fixture.runId()),
                String.class);

        adapter.assess(taskOf(fixture));
        adapter.assess(taskOf(fixture));

        assertThat(count("SELECT COUNT(*) FROM answers WHERE project_id = :p",
                Map.of("p", fixture.projectId()))).isEqualTo(answersBefore);
        assertThat(count("SELECT COUNT(*) FROM workspace_run_steps WHERE run_id = :r",
                Map.of("r", fixture.runId()))).isEqualTo(stepsBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_runs WHERE id = :r",
                Map.of("r", fixture.runId()), String.class)).isEqualTo(runStatusBefore);
    }

    @Test
    void stepPreparationNeverDowngradesASucceededStep() {
        Fixture fixture = fixture();
        UUID answerId = insertAnswer(fixture);
        steps.prepare(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED, "h", 1,
                WorkspaceRunStepRetryClass.SAFE);
        steps.markSucceeded(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED, 1,
                WorkspaceRunSteps.answerRef(answerId));

        // 接管后重放同一步骤的"准备"阶段:绝不把已完成退回未完成。
        steps.prepare(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED, "h", 2,
                WorkspaceRunStepRetryClass.SAFE);

        assertThat(steps.find(fixture.runId(), WorkspaceRunSteps.ANSWER_PERSISTED)
                .orElseThrow().state()).isEqualTo(WorkspaceRunStepState.SUCCEEDED);
    }

    @Test
    void stepPreparationRejectsADifferentInputHash() {
        Fixture fixture = fixture();
        steps.prepare(fixture.runId(), WorkspaceRunSteps.STATE_UPDATE_MODEL, "hash-a", 1,
                WorkspaceRunStepRetryClass.UNSAFE);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        steps.prepare(fixture.runId(), WorkspaceRunSteps.STATE_UPDATE_MODEL,
                                "hash-b", 2, WorkspaceRunStepRetryClass.UNSAFE))
                .as("reusing a step with different input must be refused")
                .isInstanceOf(IllegalStateException.class);
    }

    private long count(String sql, Map<String, Object> params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }
}
