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
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-first integration coverage for the "resume a historical unanswered
 * Question" operation.
 *
 * <p>This operation is a route-only mutation: a new route becomes the active
 * one and inherits a frozen snapshot of the source route's prefix answers,
 * without rewriting the original route's lineage and without copying or
 * retracting the canonical Question.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RouteResumeQuestionApiIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteService routeService;
    @Autowired private GraphOperationRepository operationRepository;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private Project project;
    private Route sourceRoute;
    private Node root;
    private Node target;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        project = projectService.createProject("Resume Question 测试");
        sourceRoute = routeRepository.findById(project.activeRouteId()).orElseThrow();
        // Lineage: root -> target (both AGENT-authored INTERACTION/QUESTION).
        root = nodeService.createRootNode(
                project.id(), sourceRoute.id(), "首要问题", "P0",
                List.of(), true);
        target = nodeService.createChildNode(
                project.id(), sourceRoute.id(), root.id(), "历史问题", "P1",
                List.of(), true);
    }

    /**
     * Scenario A: source route is OPEN, its tip is the target Question, and
     * the target has no finalized effective answer. Resume must NOT create a
     * new route — it just reactivates the existing one and updates Active.
     */
    @Test
    void scenarioA_activatesExistingRouteWithoutCreatingNewRoute() throws Exception {
        // sourceRoute tip is `target` (last created child), not yet answered.
        UUID beforeActive = projectService.getProject(project.id()).orElseThrow().activeRouteId();

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/resume", project.id(), target.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("sourceRouteId", sourceRoute.id().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.id").value(sourceRoute.id().toString()))
                .andExpect(jsonPath("$.resumedNewRoute").value(false))
                .andExpect(jsonPath("$.activeRouteId").value(sourceRoute.id().toString()));

        // The active route is now sourceRoute.
        UUID afterActive = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        assertThat(afterActive).isEqualTo(sourceRoute.id());
        // Tip is unchanged.
        Route reloaded = routeRepository.findById(sourceRoute.id()).orElseThrow();
        assertThat(reloaded.tipNodeId()).isEqualTo(target.id());
        // No new GraphOperation was appended for an activate-only resume.
        List<GraphOperation> ops = operationRepository.findByProject(project.id());
        assertThat(ops.stream()
                .anyMatch(op -> op.type() == GraphOperation.Type.RESUME_QUESTION_FROM_HISTORY))
                .isFalse();
    }

    /**
     * Scenario B: source route is OPEN, target is a non-tip historical
     * Question. Resume must create a new RESUME_QUESTION route whose tip is
     * the same canonical target, with inherited prefix that excludes the
     * target itself.
     */
    @Test
    void scenarioB_createsResumeRouteWithInheritedPrefixExcludingTarget() throws Exception {
        // Advance the source route so target is now non-tip.
        Node grandChild = nodeService.createChildNode(
                project.id(), sourceRoute.id(), target.id(), "已推进的下一题", "P2",
                List.of(), true);
        // Answer the root on the source route so the inherited prefix has content.
        answerService.finalizeAnswer(project.id(), sourceRoute.id(), root.id(),
                null, "root answer on source", "tester");

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/resume", project.id(), target.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("sourceRouteId", sourceRoute.id().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumedNewRoute").value(true))
                .andExpect(jsonPath("$.route.tipNodeId").value(target.id().toString()))
                .andExpect(jsonPath("$.route.sourceRouteId").value(sourceRoute.id().toString()))
                .andExpect(jsonPath("$.route.branchAtNodeId").value(target.id().toString()))
                .andExpect(jsonPath("$.activeRouteId").value(
                        org.hamcrest.Matchers.not(sourceRoute.id().toString())));

        // Source route tip and identity are unchanged.
        Route sourceReloaded = routeRepository.findById(sourceRoute.id()).orElseThrow();
        assertThat(sourceReloaded.tipNodeId()).isEqualTo(grandChild.id());
        // The target Question is the same canonical id and not retracted.
        Node targetReloaded = nodeRepository.findById(target.id()).orElseThrow();
        assertThat(targetReloaded.isRetracted()).isFalse();

        // GraphOperation.RESUME_QUESTION_FROM_HISTORY recorded for the new route.
        GraphOperation op = operationRepository.findByProject(project.id()).stream()
                .filter(o -> o.type() == GraphOperation.Type.RESUME_QUESTION_FROM_HISTORY)
                .findFirst().orElseThrow();
        assertThat(op.reversible()).isTrue();
        assertThat(op.targets()).contains(target.id());
    }

    /**
     * Resume must fail closed when the target Question already has a finalized
     * effective answer on the source route.
     */
    @Test
    void resumeRejectedWhenTargetAlreadyAnsweredOnSource() throws Exception {
        answerService.finalizeAnswer(project.id(), sourceRoute.id(), target.id(),
                null, "already answered", "tester");

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/resume", project.id(), target.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("sourceRouteId", sourceRoute.id().toString()))))
                .andExpect(status().isConflict());
    }

    /**
     * Resume must fail closed (404) when the source route does not belong to
     * the project, mirroring the existing CommandExecution.requireRouteInProject
     * contract.
     */
    @Test
    void resumeRejectedWith404WhenSourceRouteForeign() throws Exception {
        UUID otherProject = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/resume", project.id(), target.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("sourceRouteId", otherProject.toString()))))
                .andExpect(status().isNotFound());
    }

    /**
     * Resume must fail closed (400) when the target is not on the source
     * route's lineage. We construct a same-project INTERACTION/QUESTION node
     * on a sibling route so it bypasses the project-scope 404 but fails the
     * lineage check inside the service.
     */
    @Test
    void resumeRejectedWith400WhenTargetNotOnSourceLineage() throws Exception {
        com.specagent.route.Route siblingRoute = routeService.createRoute(
                project.id(), com.specagent.route.RouteLifecycleStatus.OPEN, "sibling");
        Node siblingRoot = nodeService.createRootNode(
                project.id(), siblingRoute.id(), "sibling Q", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/resume", project.id(), siblingRoot.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("sourceRouteId", sourceRoute.id().toString()))))
                .andExpect(status().isBadRequest());
    }

    /**
     * Resume must fail closed (404) when the target node does not exist.
     */
    @Test
    void resumeRejectedWith404WhenTargetMissing() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/resume", project.id(), UUID.randomUUID())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("sourceRouteId", sourceRoute.id().toString()))))
                .andExpect(status().isNotFound());
    }

    /**
     * Undo RESUME_QUESTION_FROM_HISTORY must soft-delete the resume route,
     * restore the previous active route pointer, and MUST NOT retract the
     * canonical target Question.
     */
    @Test
    void undoResumeDoesNotRetractCanonicalQuestion() throws Exception {
        // Advance source so target is non-tip.
        nodeService.createChildNode(
                project.id(), sourceRoute.id(), target.id(), "已推进的下一题", "P2",
                List.of(), true);
        // Capture the previous Active route id before RESUME flips Active.
        UUID previousActive = projectService.getProject(project.id()).orElseThrow().activeRouteId();

        String response = mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/resume",
                        project.id(), target.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("sourceRouteId", sourceRoute.id().toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRouteId = objectMapper.readTree(response).get("route").get("id").asText();
        UUID resumeRouteId = UUID.fromString(newRouteId);
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(resumeRouteId);

        // Undo.
        mockMvc.perform(post("/api/v1/projects/{pid}/graph-operations/undo", project.id()))
                .andExpect(status().isOk());

        Route resumeReloaded = routeRepository.findById(resumeRouteId).orElseThrow();
        assertThat(resumeReloaded.lifecycleStatus().code()).isEqualTo("deleted");
        // Target Question is NOT retracted.
        assertThat(nodeRepository.findById(target.id()).orElseThrow().isRetracted()).isFalse();
        // Previous Active is restored.
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(previousActive);
    }

    /**
     * Undo RESUME must be rejected (409) once the resumed Question has
     * received a finalized effective answer on the resume route — Answer is
     * immutable, so the route creation that anchored it must not be revoked.
     */
    @Test
    void undoResumeRejectedAfterTargetAnswered() throws Exception {
        // Advance source so target is non-tip.
        nodeService.createChildNode(
                project.id(), sourceRoute.id(), target.id(), "已推进的下一题", "P2",
                List.of(), true);

        String response = mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/resume",
                        project.id(), target.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("sourceRouteId", sourceRoute.id().toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRouteId = objectMapper.readTree(response).get("route").get("id").asText();
        UUID resumeRouteId = UUID.fromString(newRouteId);

        // Answer the target on the resume route.
        answerService.finalizeAnswer(project.id(), resumeRouteId, target.id(),
                null, "answer on resume", "tester");

        // Undo must be rejected.
        mockMvc.perform(post("/api/v1/projects/{pid}/graph-operations/undo", project.id()))
                .andExpect(status().isConflict());
    }
}
