package com.specagent.route;

import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;

/**
 * Re-answer / Fork transaction atomicity.
 *
 * <p>Both mutations perform several durable writes (route save, inherited
 * prefix refs, Question node / Active change). A failure after route creation
 * must roll the WHOLE mutation back: no orphan route, no inherited refs, no
 * new Question, source route untouched, previous Active untouched.
 *
 * <p>The failure is injected at the inherited-prefix snapshot with a spy that
 * runs the REAL write first and throws afterwards — so the regression proves
 * the already-written inherited refs are rolled back too, not just that a
 * pre-write failure leaves nothing behind.
 *
 * <p>Deliberately NOT {@code @Transactional} — the regression only proves the
 * boundary if the service transaction really commits/rolls back against the
 * database.
 */
@SpringBootTest
@ActiveProfiles("test")
class RouteMutationAtomicityIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private RouteService routeService;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // Spy over the real resolver: the injected method performs the real
    // durable write and then fails, everything else keeps production behavior.
    @SpyBean private RouteHistoryResolver routeHistoryResolverSpy;

    @Test
    void reanswerRollsBackEveryDurableWriteWhenQuestionCreationFails() {
        Project project = projectService.createProject("Reanswer atomicity " + UUID.randomUUID());
        UUID activeRouteId = project.activeRouteId();
        Node question = nodeService.createRootNode(
                project.id(), activeRouteId, "原始问题?", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), activeRouteId, question.id(),
                null, "第一个回答", "user");

        long routesBefore = countRoutes(project.id());
        long nodesBefore = countNodes(project.id());
        long inheritedBefore = countInheritedRefs(project.id());
        Route sourceBefore = routeRepository.findById(activeRouteId).orElseThrow();

        // Force a failure AFTER the new route row AND its inherited prefix
        // were written but BEFORE the mutation completes: the real prefix
        // snapshot runs, then the injection throws.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("reanswer mutation exploded");
        }).when(routeHistoryResolverSpy).snapshotInheritedPrefix(
                any(UUID.class), any(UUID.class), any(UUID.class), anyBoolean());

        assertThatThrownBy(() -> routeService.reanswerFromNode(
                project.id(), activeRouteId, question.id(), "重新回答"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reanswer mutation exploded");

        // No new route remains.
        assertThat(countRoutes(project.id())).isEqualTo(routesBefore);
        // No inherited refs remain (they were written, then rolled back).
        assertThat(countInheritedRefs(project.id())).isEqualTo(inheritedBefore);
        // No new Question remains.
        assertThat(countNodes(project.id())).isEqualTo(nodesBefore);
        // Source route unchanged.
        Route sourceAfter = routeRepository.findById(activeRouteId).orElseThrow();
        assertThat(sourceAfter.tipNodeId()).isEqualTo(sourceBefore.tipNodeId());
        assertThat(sourceAfter.rootNodeId()).isEqualTo(sourceBefore.rootNodeId());
        assertThat(sourceAfter.lifecycleStatus()).isEqualTo(sourceBefore.lifecycleStatus());
        // Previous Active unchanged (still the source route).
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(activeRouteId);
    }

    @Test
    void forkRollsBackRouteAndInheritedRefsWhenPrefixSnapshotFails() {
        Project project = projectService.createProject("Fork atomicity " + UUID.randomUUID());
        UUID activeRouteId = project.activeRouteId();
        Node question = nodeService.createRootNode(
                project.id(), activeRouteId, "分支问题?", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), activeRouteId, question.id(),
                null, "分支回答", "user");

        long routesBefore = countRoutes(project.id());
        long inheritedBefore = countInheritedRefs(project.id());
        Route sourceBefore = routeRepository.findById(activeRouteId).orElseThrow();

        // Force a failure mid-mutation: the fork route row and its inherited
        // prefix are written, then the write fails before the Active pointer
        // moves.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("fork mutation exploded");
        }).when(routeHistoryResolverSpy).snapshotInheritedPrefix(
                any(UUID.class), any(UUID.class), any(UUID.class), anyBoolean());

        assertThatThrownBy(() -> routeService.forkFromNode(
                project.id(), activeRouteId, question.id(), "分支"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fork mutation exploded");

        // No new route remains.
        assertThat(countRoutes(project.id())).isEqualTo(routesBefore);
        // No inherited refs remain (they were written, then rolled back).
        assertThat(countInheritedRefs(project.id())).isEqualTo(inheritedBefore);
        // Source route unchanged.
        Route sourceAfter = routeRepository.findById(activeRouteId).orElseThrow();
        assertThat(sourceAfter.tipNodeId()).isEqualTo(sourceBefore.tipNodeId());
        assertThat(sourceAfter.rootNodeId()).isEqualTo(sourceBefore.rootNodeId());
        // Previous Active unchanged (the Active update is the last durable
        // write, so a mid-mutation failure must never move it).
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(activeRouteId);
    }

    private long countRoutes(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routes WHERE project_id = ?", Long.class, projectId);
    }

    private long countNodes(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE project_id = ?", Long.class, projectId);
    }

    private long countInheritedRefs(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_inherited_answers "
                        + "WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)",
                Long.class, projectId);
    }
}