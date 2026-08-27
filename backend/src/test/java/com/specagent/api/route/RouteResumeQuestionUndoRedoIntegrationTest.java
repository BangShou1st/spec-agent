package com.specagent.api.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.graph.GraphOperation;
import com.specagent.graph.GraphOperationRepository;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteBranchType;
import com.specagent.route.RouteInheritedAnswer;
import com.specagent.route.RouteInheritedAnswerRepository;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exhaustive integration coverage for the {@code RESUME_QUESTION_FROM_HISTORY}
 * Undo/Redo compensation. The compensation is route-only (it must never
 * retract the canonical Question or delete any immutable answer) and the
 * Redo path is gated by an exact-equality check on the previously-active
 * route pointer (no "X or null" fallback).
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>A. Zero inherited refs: target is the first Question on the source
 *       route. Operation is legal, Undo and Redo both succeed, the
 *       canonical Question is never retracted.</li>
 *   <li>B. Non-empty inherited refs: operation's afterRefs records the
 *       exact expected refs. Undo/Redo enforces provenance consistency
 *       (the recorded list — possibly empty — must match the actual refs).</li>
 *   <li>C. Active pointer guard: a user-side activate after RESUME blocks
 *       Undo; a user-side activate after Undo blocks Redo. Exact-equality
 *       check, not a permissive "X or null".</li>
 *   <li>D. Immutable history: a route-local Answer on the resumed Question,
 *       or a continuation on the resumed route, refuses Undo.</li>
 *   <li>E. previousActiveRouteId lifecycle: when the previously-active
 *       route is no longer OPEN, Undo fails closed BEFORE any mutation.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RouteResumeQuestionUndoRedoIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private RouteService routeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteInheritedAnswerRepository inheritedAnswerRepository;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private GraphOperationRepository operationRepository;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private Project project;
    private Route sourceRoute;
    private Node root;
    private Node target;

    /**
     * Build a source route with root → target. The source is OPEN and
     * Active; target is the current tip. Callers advance state further
     * per test.
     */
    private void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        project = projectService.createProject("Resume UndoRedo");
        sourceRoute = routeRepository.findById(project.activeRouteId()).orElseThrow();
        root = nodeService.createRootNode(
                project.id(), sourceRoute.id(), "首要问题", "P0", List.of(), true);
        target = nodeService.createChildNode(
                project.id(), sourceRoute.id(), root.id(), "历史问题", "P1", List.of(), true);
    }

    private UUID resume(UUID sourceRouteId, UUID targetNodeId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/resume",
                        project.id(), targetNodeId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("sourceRouteId", sourceRouteId.toString()))))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(res.getResponse().getContentAsString())
                        .get("route").get("id").asText());
    }

    private void undo() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{pid}/graph-operations/undo", project.id()))
                .andReturn();
    }

    private void expectUndoConflict() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{pid}/graph-operations/undo", project.id()))
                .andExpect(status().isConflict());
    }

    private void expectRedoConflict() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{pid}/graph-operations/redo", project.id()))
                .andExpect(status().isConflict());
    }

    private GraphOperation latestResumeOp() {
        return operationRepository.findByProject(project.id()).stream()
                .filter(op -> op.type() == GraphOperation.Type.RESUME_QUESTION_FROM_HISTORY)
                .max((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .orElseThrow();
    }

    // ------------------------------------------------------------------
    // A. Zero inherited refs is legal; Undo and Redo both succeed
    // ------------------------------------------------------------------

    @Test
    void a_zeroInheritedRefs_undoAndRedoBothSucceed() throws Exception {
        setUp();
        // target is the source tip. Make target non-tip and create a new
        // tip; this makes target a historical non-tip Question with no
        // finalized effective answer on the source.
        nodeService.createChildNode(project.id(), sourceRoute.id(), target.id(),
                "已推进的下一题", "P2", List.of(), true);
        // No finalizeAnswer on the source: effective = {} for the lineage
        // through target. snapshotInheritedPrefix therefore yields an
        // empty refs list (legal initial state).

        UUID resumeRouteId = resume(sourceRoute.id(), target.id());
        // No inherited refs were recorded on the new route.
        assertThat(inheritedAnswerRepository.findByBranchRouteId(resumeRouteId)).isEmpty();

        // The operation log records the expected (empty) refs list.
        GraphOperation op = latestResumeOp();
        @SuppressWarnings("unchecked")
        List<Object> expected = (List<Object>) op.afterRefs().get("expectedInheritedRefs");
        assertThat(expected).isNotNull().isEmpty();

        // Undo: route DELETED, target Question not retracted, Active restored
        // to sourceRoute.
        undo();
        Route reloaded = routeRepository.findById(resumeRouteId).orElseThrow();
        assertThat(reloaded.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.DELETED);
        assertThat(nodeRepository.findById(target.id()).orElseThrow().isRetracted()).isFalse();
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(sourceRoute.id());

        // Redo: route restored, Active = resume route again.
        mockMvc.perform(post("/api/v1/projects/{pid}/graph-operations/redo", project.id()))
                .andExpect(status().isOk());
        Route reloadedRedo = routeRepository.findById(resumeRouteId).orElseThrow();
        assertThat(reloadedRedo.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(resumeRouteId);
        // Canonical Question is still not retracted after a full round-trip.
        assertThat(nodeRepository.findById(target.id()).orElseThrow().isRetracted()).isFalse();
    }

    // ------------------------------------------------------------------
    // B. Non-empty inherited refs; provenance must round-trip exactly
    // ------------------------------------------------------------------

    @Test
    void b_nonEmptyInheritedRefs_provenanceRoundTripsThroughUndoRedo() throws Exception {
        setUp();
        nodeService.createChildNode(project.id(), sourceRoute.id(), target.id(),
                "已推进的下一题", "P2", List.of(), true);
        // Answer root on the source route so the inherited prefix has
        // exactly one ref (the root's answer).
        answerService.finalizeAnswer(project.id(), sourceRoute.id(), root.id(),
                null, "root answer", "tester");

        UUID resumeRouteId = resume(sourceRoute.id(), target.id());
        List<RouteInheritedAnswer> refsAfterResume = inheritedAnswerRepository
                .findByBranchRouteId(resumeRouteId);
        assertThat(refsAfterResume).hasSize(1);
        // The single ref must reference root's answer on the source route.
        assertThat(refsAfterResume.get(0).nodeId()).isEqualTo(root.id());
        assertThat(refsAfterResume.get(0).ownerRouteId()).isEqualTo(sourceRoute.id());

        // The operation log records the expected (non-empty) refs list
        // exactly — same nodeId / answerId / ownerRouteId / ordinal.
        GraphOperation op = latestResumeOp();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> expected =
                (List<Map<String, Object>>) op.afterRefs().get("expectedInheritedRefs");
        assertThat(expected).hasSize(1);
        assertThat(expected.get(0).get("nodeId")).isEqualTo(root.id().toString());
        assertThat(expected.get(0).get("ownerRouteId")).isEqualTo(sourceRoute.id().toString());
        assertThat(expected.get(0).get("ordinal")).isEqualTo(0);

        // Undo / Redo must preserve the exact provenance: refs survive the
        // round-trip unchanged (we never delete them, never insert extras).
        undo();
        assertThat(inheritedAnswerRepository.findByBranchRouteId(resumeRouteId)).hasSize(1);
        mockMvc.perform(post("/api/v1/projects/{pid}/graph-operations/redo", project.id()))
                .andExpect(status().isOk());
        List<RouteInheritedAnswer> refsAfterRedo = inheritedAnswerRepository
                .findByBranchRouteId(resumeRouteId);
        assertThat(refsAfterRedo).hasSize(1);
        assertThat(refsAfterRedo.get(0).nodeId()).isEqualTo(root.id());
        assertThat(refsAfterRedo.get(0).answerId()).isEqualTo(refsAfterResume.get(0).answerId());
    }

    // ------------------------------------------------------------------
    // C. Active pointer guard: exact equality, not "X or null"
    // ------------------------------------------------------------------

    @Test
    void c_undoRejectedWhenUserActivatesAnotherRouteAfterResume() throws Exception {
        setUp();
        nodeService.createChildNode(project.id(), sourceRoute.id(), target.id(),
                "已推进的下一题", "P2", List.of(), true);

        UUID resumeRouteId = resume(sourceRoute.id(), target.id());
        // The user now activates a different route. The previously-active
        // (sourceRoute) is no longer the current active; we cannot honor a
        // Undo that would change Active.
        // Create a sibling route and activate it.
        Route otherRoute = routeService.createRoute(project.id(),
                RouteLifecycleStatus.OPEN, "Other route");
        routeService.setActiveRoute(project.id(), otherRoute.id());
        // Resume route is no longer the active route.
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(otherRoute.id());

        // Undo must be rejected: the recorded "previous active" is the
        // sourceRoute, not the new otherRoute. Exact-equality: we do NOT
        // silently fall back to "previousActive X or null" → restore null.
        expectUndoConflict();
        // The resume route is still OPEN and Active was not changed.
        assertThat(routeRepository.findById(resumeRouteId).orElseThrow().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.OPEN);
    }

    @Test
    void c_redoRejectedWhenUserActivatesAnotherRouteAfterUndo() throws Exception {
        setUp();
        nodeService.createChildNode(project.id(), sourceRoute.id(), target.id(),
                "已推进的下一题", "P2", List.of(), true);

        resume(sourceRoute.id(), target.id());
        // After Undo, the previousActive route (sourceRoute) is the active
        // again. Now user activates another route. Redo must be rejected.
        undo();
        Route otherRoute = routeService.createRoute(project.id(),
                RouteLifecycleStatus.OPEN, "Other route");
        routeService.setActiveRoute(project.id(), otherRoute.id());
        expectRedoConflict();
    }

    // ------------------------------------------------------------------
    // D. Immutable history: answered target or advanced tip refuses Undo
    // ------------------------------------------------------------------

    @Test
    void d_undoRejectedWhenResumedQuestionAlreadyAnswered() throws Exception {
        setUp();
        nodeService.createChildNode(project.id(), sourceRoute.id(), target.id(),
                "已推进的下一题", "P2", List.of(), true);

        UUID resumeRouteId = resume(sourceRoute.id(), target.id());
        // Record an answer on the resumed Question.
        answerService.finalizeAnswer(project.id(), resumeRouteId, target.id(),
                null, "answer on resume", "tester");
        expectUndoConflict();
    }

    @Test
    void d_undoRejectedWhenResumedRouteAdvanced() throws Exception {
        setUp();
        nodeService.createChildNode(project.id(), sourceRoute.id(), target.id(),
                "已推进的下一题", "P2", List.of(), true);

        UUID resumeRouteId = resume(sourceRoute.id(), target.id());
        // Append a continuation onto the resume route so tip != target.
        nodeService.createChildNode(project.id(), resumeRouteId, target.id(),
                "Resume continuation", "P3", List.of(), true);
        expectUndoConflict();
    }

    // ------------------------------------------------------------------
    // E. previousActiveRouteId lifecycle guard (fail closed BEFORE mutation)
    // ------------------------------------------------------------------

    @Test
    void e_undoFailsClosedWhenPreviousActiveIsNoLongerOpen() throws Exception {
        setUp();
        nodeService.createChildNode(project.id(), sourceRoute.id(), target.id(),
                "已推进的下一题", "P2", List.of(), true);

        UUID resumeRouteId = resume(sourceRoute.id(), target.id());
        // After Resume, the user archived sourceRoute. previousActiveRouteId
        // is recorded as sourceRoute; before the user undoes, sourceRoute is
        // no longer OPEN. Undo must fail closed WITHOUT mutating state.
        routeService.archiveRoute(project.id(), sourceRoute.id());
        // To exercise the precondition (currentActive == resumedRouteId),
        // we must also keep the resume route active: re-activate it.
        routeService.setActiveRoute(project.id(), resumeRouteId);

        expectUndoConflict();
        // Resume route must remain OPEN (no softDelete happened).
        assertThat(routeRepository.findById(resumeRouteId).orElseThrow().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.OPEN);
        // Active pointer unchanged.
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(resumeRouteId);
    }
}
