package com.specagent.api.route;

import com.specagent.agent.AgentRunService;
import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.agent.FakeAnswerRunResult;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.FakeModelAdapter;
import com.specagent.common.Ids;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.context.ContextSnapshotRepository;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regenerate command integration tests.
 *
 * <p>Verifies the frozen regenerate semantics: old route SUPERSEDED,
 * replacement route OPEN and active, replacement node supersedes the target,
 * parent lineage retained, and old answer/patch/child-sibling content excluded
 * from the frozen regenerate context. Deterministic regenerate makes zero
 * model calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegenerateApiIntegrationTest {

    private static final String OLD_ANSWER_SENTINEL = "REGEN_OLD_ANSWER_DO_NOT_LEAK_3c1e";
    private static final String REGEN_INSTRUCTION = "Ask this in a more specific way about operators";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private FakeAgentOrchestrator orchestrator;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private ContextSnapshotRepository contextSnapshotRepository;

    @SpyBean
    private FakeModelAdapter fakeModelAdapter;

    private final List<ModelRequest> captured = new ArrayList<>();

    @BeforeEach
    void captureModelRequests() {
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(fakeModelAdapter).run(any(ModelRequest.class));
    }

    private record RegenerateSetup(Project project, Node root, Node target, Node targetChild,
                                   UUID sourceRouteId, UUID oldAnswerId, UUID oldPatchId) {
    }

    private RegenerateSetup buildLineageWithAnsweredTarget() {
        Project project = projectService.createProject("Regenerate project");
        Node root = orchestrator.draftNextQuestion(project.id()).producedNode();
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "Root answer stays");
        FakeAnswerRunResult targetRun = orchestrator.answerActiveNodeAndDraftNext(
                project.id(), OLD_ANSWER_SENTINEL + " the replaced answer");
        Node target = nodeService.getNode(targetRun.answer().nodeId()).orElseThrow();
        Node targetChild = targetRun.producedNode();
        return new RegenerateSetup(project, root, target, targetChild,
                targetRun.run().routeId(), targetRun.answer().id(), targetRun.patch().id());
    }

    @Test
    void regenerateSucceedsWithFrozenSemanticsAndIsolation() throws Exception {
        RegenerateSetup s = buildLineageWithAnsweredTarget();
        int modelCallsBefore = captured.size();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate",
                        s.project().id(), s.target().id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRouteId": "%s",
                                  "instruction": "%s",
                                  "replacementQuestion": "A sharper replacement question",
                                  "replacementPurpose": "A sharper purpose",
                                  "replacementOptions": [
                                    {"label": "Option label", "impact": "Option impact"}
                                  ]
                                }
                                """.formatted(s.sourceRouteId(), REGEN_INSTRUCTION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldRoute.lifecycleStatus").value("superseded"))
                .andExpect(jsonPath("$.oldRoute.isActive").value(false))
                .andExpect(jsonPath("$.replacementRoute.lifecycleStatus").value("open"))
                .andExpect(jsonPath("$.replacementRoute.isActive").value(true))
                .andExpect(jsonPath("$.replacementNode.supersedesNodeId")
                        .value(s.target().id().toString()))
                .andExpect(jsonPath("$.replacementNode.parentNodeId")
                        .value(s.root().id().toString()))
                .andExpect(jsonPath("$.replacementNode.options[0].id").exists())
                .andExpect(jsonPath("$.replacementNode.options[0].label").value("Option label"))
                .andExpect(jsonPath("$.replacementNode.question").value("A sharper replacement question"));

        // Deterministic regenerate makes no model calls.
        assertThat(captured.size()).as("regenerate must not call the model")
                .isEqualTo(modelCallsBefore);

        Route replacementRoute = routeService.listRoutes(s.project().id()).stream()
                .filter(r -> r.id().equals(
                        projectService.getProject(s.project().id()).orElseThrow().activeRouteId()))
                .findFirst()
                .orElseThrow();
        assertThat(replacementRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        Route oldRoute = routeService.getRoute(s.sourceRouteId()).orElseThrow();
        assertThat(oldRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);

        // Frozen regenerate context: parent lineage only.
        ContextSnapshot regen = contextSnapshotRepository
                .findByRoute(replacementRoute.id()).stream()
                .filter(snapshot -> snapshot.operationType() == ContextOperationType.REGENERATE)
                .findFirst()
                .orElseThrow();
        assertThat(regen.includedNodeIds())
                .contains(s.root().id())
                .doesNotContain(s.target().id())
                .doesNotContain(s.targetChild().id());
        assertThat(regen.includedAnswerIds())
                .doesNotContain(s.oldAnswerId());
        assertThat(regen.includedPatchIds())
                .doesNotContain(s.oldPatchId());
        assertThat(regen.specialInputs())
                .contains(REGEN_INSTRUCTION)
                .contains(s.target().question())
                .doesNotContain(OLD_ANSWER_SENTINEL);
    }

    @Test
    void regenerateUnknownNodeRejected() throws Exception {
        Project project = projectService.createProject("Regenerate unknown node");

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate",
                        project.id(), Ids.random())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sourceRouteId": "%s", "replacementQuestion": "Replacement question"}
                                """.formatted(project.activeRouteId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));
    }

    @Test
    void regenerateNodeFromAnotherProjectRejected() throws Exception {
        Project projectA = projectService.createProject("Regenerate owner A");
        Project projectB = projectService.createProject("Regenerate owner B");
        Node nodeA = orchestrator.draftNextQuestion(projectA.id()).producedNode();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate",
                        projectB.id(), nodeA.id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sourceRouteId": "%s", "replacementQuestion": "Replacement question"}
                                """.formatted(projectB.activeRouteId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));
    }

    @Test
    void regenerateRootNodeRemainsUnsupported() throws Exception {
        Project project = projectService.createProject("Regenerate root project");
        Node root = orchestrator.draftNextQuestion(project.id()).producedNode();
        assertThat(root.isRoot()).isTrue();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate",
                        project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sourceRouteId": "%s", "replacementQuestion": "Replacement question"}
                                """.formatted(project.activeRouteId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGENERATE_ROOT_NOT_SUPPORTED"));
    }

    @Test
    void regenerateWithoutOpenSourceRouteRejected() throws Exception {
        Project project = projectService.createProject("Regenerate no open route");
        Node root = orchestrator.draftNextQuestion(project.id()).producedNode();
        // Answer the root; the child A produced by the run lives on route R1.
        FakeAnswerRunResult targetRun = orchestrator.answerActiveNodeAndDraftNext(
                project.id(), "Branch content");
        Node target = targetRun.producedNode();
        assertThat(target.parentNodeId()).isEqualTo(root.id());
        UUID r1RouteId = targetRun.run().routeId();

        // Fork from root (R2 active); archive R1 so no OPEN route contains A.
        Route fork = routeService.forkFromNode(project.id(), r1RouteId, root.id(), "Fork");
        assertThat(fork.id()).isNotEqualTo(r1RouteId);
        routeService.archiveRoute(project.id(), r1RouteId);

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate",
                        project.id(), target.id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sourceRouteId": "%s", "replacementQuestion": "Replacement question"}
                                """.formatted(r1RouteId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNTIME_CONFLICT"));
    }

    @Test
    void regenerateBlankReplacementQuestionRejected() throws Exception {
        Project project = projectService.createProject("Regenerate blank question");
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "content");
        Node target = nodeService.getNode(routeService.getRoute(project.activeRouteId())
                .orElseThrow().tipNodeId()).orElseThrow();
        // The tip is a non-root child node at this point.
        assertThat(target.isRoot()).isFalse();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate",
                        project.id(), target.id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sourceRouteId": "%s", "replacementQuestion": "   "}
                                """.formatted(project.activeRouteId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void regenerateBlankReplacementOptionLabelRejected() throws Exception {
        Project project = projectService.createProject("Regenerate blank option");
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "content");
        Node target = nodeService.getNode(routeService.getRoute(project.activeRouteId())
                .orElseThrow().tipNodeId()).orElseThrow();
        assertThat(target.isRoot()).isFalse();

        // Bean Validation cascades into replacement options, so the blank label
        // violates ReplacementOptionRequest.@NotBlank at the controller.
        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate",
                        project.id(), target.id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRouteId": "%s",
                                  "replacementQuestion": "Replacement question",
                                  "replacementOptions": [{"label": "  ", "impact": "x"}]
                                }
                                """.formatted(project.activeRouteId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void regenerateOversizedReplacementOptionLabelRejected() throws Exception {
        Project project = projectService.createProject("Regenerate oversized label");
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "content");
        Node target = nodeService.getNode(routeService.getRoute(project.activeRouteId())
                .orElseThrow().tipNodeId()).orElseThrow();
        assertThat(target.isRoot()).isFalse();
        int routesBefore = routeService.listRoutes(project.id()).size();
        UUID sourceRouteId = project.activeRouteId();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate",
                        project.id(), target.id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRouteId": "%s",
                                  "replacementQuestion": "Replacement question",
                                  "replacementOptions": [{"label": "%s", "impact": "x"}]
                                }
                                """.formatted(project.activeRouteId(), "x".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // The rejected request must not create a replacement route/node or
        // supersede the original route.
        assertNoReplacementSideEffects(project.id(), sourceRouteId, target.id(), routesBefore);
    }

    @Test
    void regenerateOversizedReplacementOptionImpactRejected() throws Exception {
        Project project = projectService.createProject("Regenerate oversized impact");
        orchestrator.draftNextQuestion(project.id());
        orchestrator.answerActiveNodeAndDraftNext(project.id(), "content");
        Node target = nodeService.getNode(routeService.getRoute(project.activeRouteId())
                .orElseThrow().tipNodeId()).orElseThrow();
        assertThat(target.isRoot()).isFalse();
        int routesBefore = routeService.listRoutes(project.id()).size();
        UUID sourceRouteId = project.activeRouteId();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/regenerate",
                        project.id(), target.id())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRouteId": "%s",
                                  "replacementQuestion": "Replacement question",
                                  "replacementOptions": [{"label": "x", "impact": "%s"}]
                                }
                                """.formatted(project.activeRouteId(), "x".repeat(2001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // The rejected request must not create a replacement route/node or
        // supersede the original route.
        assertNoReplacementSideEffects(project.id(), sourceRouteId, target.id(), routesBefore);
    }

    private void assertNoReplacementSideEffects(UUID projectId, UUID sourceRouteId,
                                                UUID targetNodeId, int routesBefore) {
        List<Route> routes = routeService.listRoutes(projectId);
        assertThat(routes).hasSize(routesBefore);
        Route sourceRoute = routeService.getRoute(sourceRouteId).orElseThrow();
        assertThat(sourceRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(sourceRoute.tipNodeId()).isEqualTo(targetNodeId);
        assertThat(nodeService.getNode(targetNodeId).orElseThrow().supersedesNodeId()).isNull();
    }
}
