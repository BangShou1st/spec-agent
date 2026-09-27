package com.specagent.agent.runtime;

import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:LegacyReplayIntegrationTest.java
 *
 * 测试目标:验证旧版(LEGACY)run 的重放行为:缺少冻结输入投影的旧 run 续跑时
 * 必须以类型化错误 LEGACY_FROZEN_INPUT_UNAVAILABLE 失败,且不产生重复的答案/patch
 * 工件;带 snapshotId 但无投影的旧 run 同样类型化失败;从未被消费的快照仍按
 * 常规流程完成首次冻结。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LegacyReplayIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private AnswerService answerService;
    @Autowired private AnswerPatchService answerPatchService;
    @Autowired private com.specagent.workspace.context.ContextBuilder contextBuilder;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.specagent.agent.snapshot.AgentInputSnapshotBuilder snapshotBuilder;

    private Project project;
    private Route route;
    private Node question;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("legacy-replay-" + UUID.randomUUID());
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        question = nodeService.createRootNode(project.id(), route.id(),
                "legacy question?", null, List.of(), true);
    }

    @Test
    void legacyDecisionReplay_failsWithTypedErrorAndNoSecondArtifacts() {
        var answer = answerService.finalizeAnswer(project.id(), route.id(), question.id(), null, "legacy answer", "user");
        var patch = answerPatchService.save(project.id(), route.id(), question.id(), answer.id(),
                List.of(Claim.of(ClaimKind.GOAL, "legacy claim", ClaimStatus.CONFIRMED, question.id(), answer.id())), null);

        UUID legacyRunId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agent_runs (id, project_id, route_id, trigger_type, input_node_id, status, trace, created_at) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                legacyRunId, project.id(), route.id(), "answer_cycle", question.id(), "persisted", "\"created\"", Timestamp.from(Instant.now()));
        jdbcTemplate.update(
                "UPDATE agent_runs SET produced_answer_id = ?, produced_patch_id = ? WHERE id = ?",
                answer.id(), patch.id(), legacyRunId);
        jdbcTemplate.update(
                "INSERT INTO agent_run_events (id, run_id, sequence, phase, event_type, payload, created_at) VALUES (?, ?, 1, ?, ?, CAST(? AS jsonb), ?)",
                UUID.randomUUID(), legacyRunId, "DECIDING", "DECISION_STARTED", "{}", Timestamp.from(Instant.now()));

        int answerCountBefore = answerService.findAnswersForRouteAndNodeIds(route.id(), List.of(question.id())).size();

        runService.createQueuedRunWithInput(project.id(), "RESUME_ANSWER", question.id(), null, null, answer.id());
        var claimed = runService.claimNextAnswerCycle().orElseThrow();

        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(com.specagent.agent.snapshot.LegacyFrozenInputUnavailableException.class)
                .hasMessageContaining("LEGACY_FROZEN_INPUT_UNAVAILABLE");

        assertThat(answerService.findAnswersForRouteAndNodeIds(route.id(), List.of(question.id()))).hasSize(answerCountBefore);
        Integer patchRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answer_patches WHERE source_answer_id = ?", Integer.class, answer.id());
        assertThat(patchRows).isEqualTo(1);
    }

    @Test
    void legacyDecisionWithSnapshotIdButNoProjection_alsoFailsTyped() {
        var answer = answerService.finalizeAnswer(project.id(), route.id(), question.id(), null, "legacy 2", "user");
        var patch = answerPatchService.save(project.id(), route.id(), question.id(), answer.id(),
                List.of(Claim.of(ClaimKind.GOAL, "c", ClaimStatus.CONFIRMED, question.id(), answer.id())), null);

        UUID legacyRunId = UUID.randomUUID();
        UUID fakeSnapshotId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agent_runs (id, project_id, route_id, trigger_type, input_node_id, status, trace, created_at) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                legacyRunId, project.id(), route.id(), "answer_cycle", question.id(), "persisted", "\"created\"", Timestamp.from(Instant.now()));
        jdbcTemplate.update("UPDATE agent_runs SET produced_answer_id = ?, produced_patch_id = ? WHERE id = ?", answer.id(), patch.id(), legacyRunId);
        jdbcTemplate.update(
                "INSERT INTO agent_run_events (id, run_id, sequence, phase, event_type, payload, created_at) VALUES (?, ?, 1, ?, ?, CAST(? AS jsonb), ?)",
                UUID.randomUUID(), legacyRunId, "DECIDING", "DECISION_STARTED", "{\"snapshotId\":\"" + fakeSnapshotId + "\"}", Timestamp.from(Instant.now()));

        runService.createQueuedRunWithInput(project.id(), "RESUME_ANSWER", question.id(), null, null, answer.id());
        var claimed = runService.claimNextAnswerCycle().orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(com.specagent.agent.snapshot.LegacyFrozenInputUnavailableException.class)
                .hasMessageContaining("LEGACY_FROZEN_INPUT_UNAVAILABLE");
    }

    @Test
    void neverConsumedSnapshot_stillFirstFreezeNormally() {
        var ctx = contextBuilder.buildFromActiveRoute(project.id(), UUID.randomUUID(), com.specagent.workspace.context.ContextOperationType.NORMAL);
        var snap = snapshotBuilder.build(ctx);
        assertThat(snap.snapshotId()).isEqualTo(ctx.id().toString());
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_input_projections WHERE snapshot_id = ?", Integer.class, ctx.id());
        assertThat(rows).isEqualTo(1);
    }
}
