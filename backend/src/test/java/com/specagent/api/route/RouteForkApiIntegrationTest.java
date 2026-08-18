package com.specagent.api.route;

import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fork command integration tests. Fork stays a historical lineage view: no
 * node/answer/patch copies, old route untouched, new route becomes active.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RouteForkApiIntegrationTest {

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
    private NodeRepository nodeRepository;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private AnswerService answerService;

    @Test
    void forkFromActiveLineageCreatesActiveHistoricalView() throws Exception {
        Project project = projectService.createProject("Fork project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root question", null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), project.activeRouteId(), root.id(),
                "Child question", null, List.of(), true);
        UUID originalRouteId = project.activeRouteId();
        int nodeCountBefore = nodeRepository.findByProject(project.id()).size();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"label\": \"Alternative route\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.label").value("Alternative route"))
                .andExpect(jsonPath("$.route.lifecycleStatus").value("open"))
                .andExpect(jsonPath("$.route.isActive").value(true))
                .andExpect(jsonPath("$.route.rootNodeId").value(root.id().toString()))
                .andExpect(jsonPath("$.route.tipNodeId").value(root.id().toString()))
                .andExpect(jsonPath("$.route.createdFromNodeId").value(root.id().toString()));

        Route fork = routeService.listRoutes(project.id()).stream()
                .filter(r -> !r.id().equals(originalRouteId))
                .findFirst()
                .orElseThrow();

        // Fork semantics: tip = source node, root = source route root, old route unchanged.
        assertThat(fork.tipNodeId()).isEqualTo(root.id());
        assertThat(fork.rootNodeId()).isEqualTo(root.id());
        assertThat(fork.createdFromNodeId()).isEqualTo(root.id());
        assertThat(fork.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(fork.id());

        Route oldRoute = routeService.getRoute(originalRouteId).orElseThrow();
        assertThat(oldRoute.tipNodeId()).isEqualTo(child.id());
        assertThat(nodeRepository.findByProject(project.id())).hasSize(nodeCountBefore);
        // No answers or patches are copied onto the fork route.
        assertThat(answerRepository.findByRouteAndNodeIds(fork.id(), List.of(root.id()))).isEmpty();
        assertThat(answerPatchService.findByRoute(fork.id())).isEmpty();
    }

    @Test
    void forkDoesNotCopyAnswersOrPatchesFromSourceRoute() throws Exception {
        Project project = projectService.createProject("Fork copy check");
        Node root = orchestrator.draftNextQuestion(project.id()).producedNode();
        UUID sourceRouteId = project.activeRouteId();
        var answerResult = orchestrator.answerActiveNodeAndDraftNext(project.id(), "Root answer content");
        Answer answer = answerResult.answer();
        AnswerPatch patch = answerResult.patch();
        assertThat(answer.routeId()).isEqualTo(sourceRouteId);
        assertThat(patch.routeId()).isEqualTo(sourceRouteId);

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        UUID forkRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        Route fork = routeService.getRoute(forkRouteId).orElseThrow();
        assertThat(fork.tipNodeId()).isEqualTo(root.id());
        // The source answer/patch stay on the source route, not the fork.
        assertThat(answerRepository.findByRouteAndNodeIds(fork.id(), List.of(root.id()))).isEmpty();
        assertThat(answerPatchService.findByRoute(fork.id())).isEmpty();
        assertThat(answerService.getAnswer(answer.id()).orElseThrow().routeId())
                .isEqualTo(sourceRouteId);
    }

    @Test
    void forkUnknownNodeRejected() throws Exception {
        Project project = projectService.createProject("Fork unknown node");

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", project.id(),
                        UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));
    }

    @Test
    void forkNodeFromAnotherProjectRejected() throws Exception {
        Project projectA = projectService.createProject("Fork owner A");
        Project projectB = projectService.createProject("Fork owner B");
        Node nodeA = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(),
                "A node", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", projectB.id(), nodeA.id())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));
    }

    @Test
    void forkIsolationSiblingContentNotImported() throws Exception {
        Project project = projectService.createProject("Fork isolation");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Shared root", null, List.of(), true);
        Node siblingA = nodeService.createChildNode(project.id(), project.activeRouteId(), root.id(),
                "Sibling branch question", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"label\": \"Root fork\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.tipNodeId").value(root.id().toString()));

        Route fork = routeService.getRoute(
                projectService.getProject(project.id()).orElseThrow().activeRouteId()).orElseThrow();
        assertThat(fork.tipNodeId()).isEqualTo(root.id());
        // The sibling node still exists but is not part of the fork history.
        assertThat(nodeRepository.findById(siblingA.id())).isPresent();
        assertThat(fork.tipNodeId()).isNotEqualTo(siblingA.id());
    }
}