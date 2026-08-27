package com.specagent.api.graph;

import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Write-time lineage invariants: an unanswered Question may never gain a
 * lineage child (it must stay a route tip), lineage must be acyclic, and
 * {@code sourceRouteId} ancestry must be acyclic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GraphLineageInvariantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private Project newProject(String title) {
        return projectService.createProject(title);
    }

    @Test
    void appendContinuationFromUnansweredQuestionRejects() throws Exception {
        Project project = newProject("Unanswered continuation");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "未答问题", "P0",
                List.of(NodeOption.of("A", "a")), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/continuation",
                        project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\"," +
                                "\"subtype\":\"NOTE\",\"content\":{\"text\":\"cross\"}}"))
                .andExpect(status().isConflict());
        // The route tip is unchanged: nothing crossed the unanswered Question.
        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId())
                .isEqualTo(root.id());
    }

    @Test
    void attachResourceFromUnansweredQuestionRejectsWhenItAdvancesLineage() throws Exception {
        Project project = newProject("Unanswered resource");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "未答问题", "P0",
                List.of(NodeOption.of("A", "a")), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/resources", project.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\"," +
                                "\"parentNodeId\":\"" + root.id() + "\"," +
                                "\"subtype\":\"TEXT\",\"content\":{\"text\":\"attachment\"}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void answeredQuestionCanAdvance() throws Exception {
        Project project = newProject("Answered advance");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "已答问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        answerService.finalizeAnswer(project.id(), routeId, root.id(), null, "answer", "user");

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/continuation",
                        project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\"," +
                                "\"subtype\":\"NOTE\",\"content\":{\"text\":\"go\"}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void forkFromUnansweredQuestionRejects() throws Exception {
        Project project = newProject("Unanswered fork");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "未答问题", "P0",
                List.of(NodeOption.of("A", "a")), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/fork", project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sourceRouteId\":\"" + routeId + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void lineageAncestorCycleIsRejectedAtWriteTime() throws Exception {
        Project project = newProject("Lineage cycle");
        UUID routeId = project.activeRouteId();
        Node a = nodeService.createRootNode(project.id(), routeId, "A", null,
                List.of(), true);
        Node b = nodeService.createChildNode(project.id(), routeId, a.id(), "B", null,
                List.of(), true);
        Node c = nodeService.createChildNode(project.id(), routeId, b.id(), "C", null,
                List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, a.id(), null, "a answer", "user");
        answerService.finalizeAnswer(project.id(), routeId, b.id(), null, "b answer", "user");
        answerService.finalizeAnswer(project.id(), routeId, c.id(), null, "c answer", "user");

        // Corrupt: A -> B -> C -> A. Any lineage-advancing command over the
        // corrupted lineage must fail closed instead of silently continuing.
        jdbc.update("UPDATE nodes SET parent_node_id = :parent WHERE id = :id",
                Map.of("parent", c.id(), "id", a.id()));

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/continuation",
                        project.id(), c.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\"," +
                                "\"subtype\":\"NOTE\",\"content\":{\"text\":\"x\"}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void routeProvenanceCycleIsRejectedWhenCreatingBranch() throws Exception {
        Project project = newProject("Provenance cycle");
        UUID routeId = project.activeRouteId();
        nodeService.createRootNode(project.id(), routeId, "根", null, List.of(), true);

        Route sibling = routeService.createRoute(project.id(),
                com.specagent.route.RouteLifecycleStatus.OPEN, "sibling");
        Node siblingRoot = nodeService.createRootNode(
                project.id(), sibling.id(), "sibling 节点", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), sibling.id(), siblingRoot.id(),
                null, "sibling answer", "user");

        // Corrupt provenance: sourceRouteId ancestry cycles
        // (sibling -> routeId -> sibling).
        jdbc.update("UPDATE routes SET source_route_id = :source WHERE id = :id",
                Map.of("source", routeId, "id", sibling.id()));
        jdbc.update("UPDATE routes SET source_route_id = :source WHERE id = :id",
                Map.of("source", sibling.id(), "id", routeId));

        // Forking from the corrupted sibling must be rejected before any write.
        assertThatThrownBy(() -> routeService.forkFromNode(
                project.id(), sibling.id(), siblingRoot.id(), "cycle fork"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROUTE_PROVENANCE_CYCLE");
    }
}