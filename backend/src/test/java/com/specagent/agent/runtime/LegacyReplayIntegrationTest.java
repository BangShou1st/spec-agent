package com.specagent.agent.runtime;

import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LegacyReplayIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private AnswerService answerService;
    @Autowired private AnswerPatchService answerPatchService;
    @Autowired private com.specagent.context.ContextBuilder contextBuilder;
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
        var ctx = contextBuilder.buildFromActiveRoute(project.id(), UUID.randomUUID(), com.specagent.context.ContextOperationType.NORMAL);
        var snap = snapshotBuilder.build(ctx);
        assertThat(snap.snapshotId()).isEqualTo(ctx.id().toString());
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_input_projections WHERE snapshot_id = ?", Integer.class, ctx.id());
        assertThat(rows).isEqualTo(1);
    }
}
