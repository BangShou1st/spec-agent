package com.specagent.api.requirement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.common.Ids;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.project.ProjectService;
import com.specagent.readmodel.requirement.RequirementClaimView;
import com.specagent.readmodel.requirement.RequirementStateView;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Requirement-state read endpoint integration tests.
 *
 * <p>Proves the endpoint is a safe, read-only, route-scoped view of the
 * backend-derived requirement state: never calls a model, never writes state,
 * never leaks sibling-route claims, and fails closed when the active pointer
 * cannot be trusted. Runs against the default fake model gateway (zero public
 * provider requests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RequirementStateApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private FakeAgentOrchestrator orchestrator;

    @Test
    void newProjectHasEmptyDerivedState() throws Exception {
        Project project = projectService.createProject("Empty state project");

        mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.confirmed").isEmpty())
                .andExpect(jsonPath("$.assumed").isEmpty())
                .andExpect(jsonPath("$.unresolved").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty())
                .andExpect(jsonPath("$.builtAt").exists());
    }

    @Test
    void groupsClaimsByActualRuntimeStatusAfterAnswer() throws Exception {
        Project project = projectService.createProject("Grouped state project");
        // Run the normal orchestrator path so the first confirmed/unresolved
        // claims are derived from a real answer (fake gateway, no provider).
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "Primary outcome answer");

        // Add an assumed and a rejected claim on the same active route through
        // the runtime patch service so every status group is populated.
        UUID activeRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        UUID childTip = routeService.getRoute(activeRouteId).orElseThrow().tipNodeId();
        Answer extraAnswer = answerService.finalizeAnswer(project.id(), activeRouteId, childTip,
                null, "Assumption and rejection content", "test");
        answerPatchService.save(project.id(), activeRouteId, childTip, extraAnswer.id(),
                List.of(Claim.of(ClaimKind.ASSUMPTION, "Assumed scope detail", ClaimStatus.ASSUMED, null, null),
                        Claim.of(ClaimKind.OTHER, "Rejected idea detail", ClaimStatus.REJECTED, null, null)),
                null);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(activeRouteId.toString()))
                .andExpect(jsonPath("$.confirmed[0].kind").value("goal"))
                .andExpect(jsonPath("$.confirmed[0].status").value("confirmed"))
                .andExpect(jsonPath("$.confirmed[0].confidence").value(0.9))
                .andExpect(jsonPath("$.confirmed[0].sourceNodeId").exists())
                .andExpect(jsonPath("$.confirmed[0].sourceAnswerId").exists())
                .andExpect(jsonPath("$.unresolved[0].kind").value("open_question"))
                .andExpect(jsonPath("$.unresolved[0].status").value("unresolved"))
                .andExpect(jsonPath("$.assumed[0].text").value("Assumed scope detail"))
                .andExpect(jsonPath("$.assumed[0].status").value("assumed"))
                .andExpect(jsonPath("$.rejected[0].text").value("Rejected idea detail"))
                .andExpect(jsonPath("$.rejected[0].status").value("rejected"))
                .andReturn();

        RequirementStateView view = objectMapper.readValue(
                result.getResponse().getContentAsString(), RequirementStateView.class);
        assertThat(view.confirmed()).extracting(RequirementClaimView::text)
                .contains("The user clarified the main outcome.");
        assertThat(view.unresolved()).extracting(RequirementClaimView::text)
                .contains("The user must confirm scope boundaries.");
        // Groups are mutually exclusive: a claim appears exactly once by its
        // actual runtime status.
        assertThat(view.confirmed()).doesNotContain(view.unresolved().get(0));
    }

    @Test
    void activeRouteStateDoesNotExposeSiblingRouteClaims() throws Exception {
        Project project = projectService.createProject("Isolation project");
        // Active route gets real derived claims through the orchestrator.
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "Active route answer");
        UUID activeRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();

        // Sibling route with unmistakable sentinel content, never activated.
        Route sibling = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "sibling route");
        Node siblingNode = nodeService.createRootNode(project.id(), sibling.id(),
                "Sibling question", null, List.of(), true);
        Answer siblingAnswer = answerService.finalizeAnswer(project.id(), sibling.id(),
                siblingNode.id(), null, "sibling sentinel answer", "test");
        answerPatchService.save(project.id(), sibling.id(), siblingNode.id(), siblingAnswer.id(),
                List.of(Claim.of(ClaimKind.GOAL, "SENTINEL_SIBLING_ONLY_CLAIM_9F3A",
                        ClaimStatus.CONFIRMED, siblingNode.id(), siblingAnswer.id())),
                null);

        // The active route pointer is unchanged and points at the original route.
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(activeRouteId);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(activeRouteId.toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("The user clarified the main outcome.");
        assertThat(body).doesNotContain("SENTINEL_SIBLING_ONLY_CLAIM_9F3A");
        assertThat(body).doesNotContain("sibling sentinel answer");
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void noActiveRouteReturnsSafeEmptyReadModel() throws Exception {
        Project project = projectService.createProject("No active route project");
        projectRepository.updateActiveRoute(project.id(), null, Instant.now());

        mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").isEmpty())
                .andExpect(jsonPath("$.confirmed").isEmpty())
                .andExpect(jsonPath("$.assumed").isEmpty())
                .andExpect(jsonPath("$.unresolved").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    @Test
    void foreignActiveRouteFailsClosedWithoutExposingData() throws Exception {
        Project projectA = projectService.createProject("Owner A");
        Project projectB = projectService.createProject("Owner B");

        // Give project B's route unmistakable sentinel content.
        UUID routeB = projectB.activeRouteId();
        Node nodeB = nodeService.createRootNode(projectB.id(), routeB,
                "B question", null, List.of(), true);
        Answer answerB = answerService.finalizeAnswer(projectB.id(), routeB,
                nodeB.id(), null, "FOREIGN_ROUTE_SENTINEL_77EE", "test");
        answerPatchService.save(projectB.id(), routeB, nodeB.id(), answerB.id(),
                List.of(Claim.of(ClaimKind.GOAL, "FOREIGN_ROUTE_SENTINEL_77EE",
                        ClaimStatus.CONFIRMED, nodeB.id(), answerB.id())),
                null);

        // Corrupt A's active pointer to point at B's route.
        projectRepository.updateActiveRoute(projectA.id(), routeB, Instant.now());

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", projectA.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("FOREIGN_ROUTE_SENTINEL_77EE");
        assertThat(body).doesNotContain("B question");
    }

    // ------------------------------------------------------------------
    // Phase 7.3A: route-scoped requirement-state reads.
    // ------------------------------------------------------------------

    private Route createRouteWithSentinelClaim(Project project, String sentinelText) {
        Route route = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN,
                "sentinel route");
        Node node = nodeService.createRootNode(project.id(), route.id(),
                "Sentinel question", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(project.id(), route.id(),
                node.id(), null, "sentinel answer " + sentinelText, "test");
        answerPatchService.save(project.id(), route.id(), node.id(), answer.id(),
                List.of(Claim.of(ClaimKind.GOAL, sentinelText,
                        ClaimStatus.CONFIRMED, node.id(), answer.id())),
                null);
        return route;
    }

    @Test
    void routeScopedReadReturnsExplicitRouteBWhileActiveRouteIsA() throws Exception {
        Project project = projectService.createProject("Route scoped project");
        // Active route A gets real derived claims through the orchestrator.
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "Active route answer");
        UUID activeRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        Route routeB = createRouteWithSentinelClaim(project, "ROUTE_B_ONLY_CLAIM_5D1F");
        assertThat(activeRouteId).isNotEqualTo(routeB.id());

        MvcResult result = mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), routeB.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(routeB.id().toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("ROUTE_B_ONLY_CLAIM_5D1F");
        // A's active-route claims never leak into the explicit B read.
        assertThat(body).doesNotContain("The user clarified the main outcome.");
    }

    @Test
    void legacyActiveEndpointStillReturnsActiveRouteA() throws Exception {
        Project project = projectService.createProject("Legacy endpoint project");
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "Active route answer");
        UUID activeRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        createRouteWithSentinelClaim(project, "ROUTE_B_ONLY_CLAIM_9B17");

        MvcResult result = mockMvc.perform(get(
                "/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(activeRouteId.toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("The user clarified the main outcome.");
        assertThat(body).doesNotContain("ROUTE_B_ONLY_CLAIM_9B17");
    }

    @Test
    void archivedRouteScopedReadRemainsAvailable() throws Exception {
        Project project = projectService.createProject("Archived scoped project");
        Route route = createRouteWithSentinelClaim(project, "ARCHIVED_ROUTE_CLAIM_3C21");
        routeService.archiveRoute(project.id(), route.id());

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), route.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(route.id().toString()))
                .andExpect(jsonPath("$.confirmed[0].text").value("ARCHIVED_ROUTE_CLAIM_3C21"));
    }

    @Test
    void supersededRouteScopedReadRemainsAvailable() throws Exception {
        Project project = projectService.createProject("Superseded scoped project");
        UUID activeRouteId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), activeRouteId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), activeRouteId, root.id(),
                "Child question", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(project.id(), activeRouteId, root.id(),
                null, "old route answer", "test");
        answerPatchService.save(project.id(), activeRouteId, root.id(), answer.id(),
                List.of(Claim.of(ClaimKind.GOAL, "OLD_ROUTE_CLAIM_6E41",
                        ClaimStatus.CONFIRMED, root.id(), answer.id())),
                null);

        routeService.regenerateFromNode(project.id(), activeRouteId, child.id(), "Regenerate",
                "Replacement question", null, List.of());
        assertThat(routeService.getRoute(activeRouteId).orElseThrow().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.SUPERSEDED);

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), activeRouteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(activeRouteId.toString()))
                .andExpect(jsonPath("$.confirmed[0].text").value("OLD_ROUTE_CLAIM_6E41"));
    }

    @Test
    void deletedRouteScopedReadRemainsAvailable() throws Exception {
        Project project = projectService.createProject("Deleted scoped project");
        Route route = createRouteWithSentinelClaim(project, "DELETED_ROUTE_CLAIM_8F22");
        routeService.softDeleteRoute(project.id(), route.id());

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), route.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(route.id().toString()))
                .andExpect(jsonPath("$.confirmed[0].text").value("DELETED_ROUTE_CLAIM_8F22"));
    }

    @Test
    void routeScopedMissingRouteReturnsNotFound() throws Exception {
        Project project = projectService.createProject("Missing route scoped project");

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void routeScopedForeignRouteReturnsNotFound() throws Exception {
        Project projectA = projectService.createProject("Owner A");
        Project projectB = projectService.createProject("Owner B");

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                projectA.id(), projectB.activeRouteId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void routeScopedUnknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                Ids.random(), Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }
}
