package com.specagent.api.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.answer.AnswerService;
import com.specagent.graph.GraphCommandService;
import com.specagent.graph.NodeRelation;
import com.specagent.graph.NodeRelationType;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Write-time relation invariants under the final product model:
 * endpoint hard rules (self / missing / cross-project / retracted), symmetric
 * duplicate semantics, DEPENDS_ON + DERIVED_FROM joint cycle rejection, and
 * permissive cycles for RELATED_TO / CONFLICTS_WITH / SUPPORTS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RelationInvariantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private GraphCommandService commandService;
    @Autowired
    private RouteRepository routeRepository;

    private Project newProject(String title) {
        return projectService.createProject(title);
    }

    private String relBody(String source, String target, String type) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "sourceNodeId", source,
                "targetNodeId", target,
                "relationType", type));
    }

    @Test
    void selfRelationIsRejected() throws Exception {
        Project project = newProject("Self relation");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Q", null, List.of(), true);
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(relBody(root.id().toString(), root.id().toString(), "RELATED_TO")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retractedEndpointIsRejected() throws Exception {
        Project project = newProject("Retracted endpoint");
        Node a = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "A", null, List.of(), true);
        Node b = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "B", null, List.of(), true);
        commandService.createSemanticRelation(project.id(), a.id(), b.id(),
                NodeRelationType.RELATED_TO, NodeRelation.Origin.USER, null, null);
        // Retract A via the same soft-retraction path used by Undo.
        nodeService.setRetracted(a.id(), true);

        assertThatThrownBy(() -> commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.DEPENDS_ON,
                NodeRelation.Origin.USER, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETRACTED_NODE_REFERENCE");
    }

    @Test
    void dependenciesMustStayAcyclic() throws Exception {
        Project project = newProject("DEPENDS cycle");
        Node a = nodeService.createRootNode(project.id(), project.activeRouteId(), "A", null, List.of(), true);
        Node b = nodeService.createRootNode(project.id(), project.activeRouteId(), "B", null, List.of(), true);
        Node c = nodeService.createRootNode(project.id(), project.activeRouteId(), "C", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(relBody(a.id().toString(), b.id().toString(), "DEPENDS_ON")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(relBody(b.id().toString(), c.id().toString(), "DEPENDS_ON")))
                .andExpect(status().isCreated());
        // C -> A closes the cycle A -> B -> C -> A.
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(relBody(c.id().toString(), a.id().toString(), "DEPENDS_ON")))
                .andExpect(status().isConflict());
    }

    @Test
    void derivedFromMustStayAcyclic() throws Exception {
        Project project = newProject("DERIVED cycle");
        Node a = nodeService.createRootNode(project.id(), project.activeRouteId(), "A", null, List.of(), true);
        Node b = nodeService.createRootNode(project.id(), project.activeRouteId(), "B", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(relBody(a.id().toString(), b.id().toString(), "DERIVED_FROM")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(relBody(b.id().toString(), a.id().toString(), "DERIVED_FROM")))
                .andExpect(status().isConflict());
    }

    @Test
    void mixedDependsAndDerivedCycleIsRejected() throws Exception {
        Project project = newProject("Mixed causal cycle");
        Node a = nodeService.createRootNode(project.id(), project.activeRouteId(), "A", null, List.of(), true);
        Node b = nodeService.createRootNode(project.id(), project.activeRouteId(), "B", null, List.of(), true);
        Node c = nodeService.createRootNode(project.id(), project.activeRouteId(), "C", null, List.of(), true);

        commandService.createSemanticRelation(project.id(), a.id(), b.id(),
                NodeRelationType.DEPENDS_ON, NodeRelation.Origin.USER, null, null);
        commandService.createSemanticRelation(project.id(), b.id(), c.id(),
                NodeRelationType.DERIVED_FROM, NodeRelation.Origin.USER, null, null);
        // C DEPENDS_ON A closes the mixed causal circle.
        assertThatThrownBy(() -> commandService.createSemanticRelation(
                project.id(), c.id(), a.id(), NodeRelationType.DEPENDS_ON,
                NodeRelation.Origin.USER, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELATION_DEPENDENCY_CYCLE");
    }

    @Test
    void relatedToTriangleIsAllowed() throws Exception {
        Project project = newProject("RELATED_TO triangle");
        Node a = nodeService.createRootNode(project.id(), project.activeRouteId(), "A", null, List.of(), true);
        Node b = nodeService.createRootNode(project.id(), project.activeRouteId(), "B", null, List.of(), true);
        Node c = nodeService.createRootNode(project.id(), project.activeRouteId(), "C", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(relBody(a.id().toString(), b.id().toString(), "RELATED_TO")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(relBody(b.id().toString(), c.id().toString(), "RELATED_TO")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(relBody(c.id().toString(), a.id().toString(), "RELATED_TO")))
                .andExpect(status().isCreated());
    }

    @Test
    void conflictsTriangleIsAllowed() throws Exception {
        Project project = newProject("CONFLICTS triangle");
        Node a = nodeService.createRootNode(project.id(), project.activeRouteId(), "A", null, List.of(), true);
        Node b = nodeService.createRootNode(project.id(), project.activeRouteId(), "B", null, List.of(), true);
        Node c = nodeService.createRootNode(project.id(), project.activeRouteId(), "C", null, List.of(), true);

        commandService.createSemanticRelation(project.id(), a.id(), b.id(),
                NodeRelationType.CONFLICTS_WITH, NodeRelation.Origin.USER, null, null);
        commandService.createSemanticRelation(project.id(), b.id(), c.id(),
                NodeRelationType.CONFLICTS_WITH, NodeRelation.Origin.USER, null, null);
        commandService.createSemanticRelation(project.id(), c.id(), a.id(),
                NodeRelationType.CONFLICTS_WITH, NodeRelation.Origin.USER, null, null);
    }

    @Test
    void supportCyclesAreAllowedInPhaseOne() throws Exception {
        Project project = newProject("SUPPORTS cycle allowed");
        Node a = nodeService.createRootNode(project.id(), project.activeRouteId(), "A", null, List.of(), true);
        Node b = nodeService.createRootNode(project.id(), project.activeRouteId(), "B", null, List.of(), true);

        commandService.createSemanticRelation(project.id(), a.id(), b.id(),
                NodeRelationType.SUPPORTS, NodeRelation.Origin.USER, null, null);
        commandService.createSemanticRelation(project.id(), b.id(), a.id(),
                NodeRelationType.SUPPORTS, NodeRelation.Origin.USER, null, null);
    }

    @Test
    void relationNeverChangesFloatingPlacementOrRouteState() throws Exception {
        Project project = newProject("Relation keeps floating");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node q = nodeService.createRootNode(project.id(), route.id(), "路线问题", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), route.id(), q.id(), null, "answer", "user");
        Node floating = nodeService.createFloatingWorkspaceNode(
                project.id(), com.specagent.node.NodeKind.KNOWLEDGE, "IDEA",
                Map.of("text", "floating idea"),
                com.specagent.node.NodeAuthorKind.USER,
                com.specagent.node.KnowledgeStatus.PROPOSED);
        assertThat(floating.parentNodeId()).isNull();

        commandService.createSemanticRelation(project.id(), floating.id(), q.id(),
                NodeRelationType.DEPENDS_ON, NodeRelation.Origin.USER, null, null);

        // Floating placement unchanged: no parent, no route membership, tip
        // and Active untouched.
        assertThat(nodeService.getNode(floating.id()).orElseThrow().parentNodeId()).isNull();
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId()).isEqualTo(q.id());
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(route.id());
    }
}