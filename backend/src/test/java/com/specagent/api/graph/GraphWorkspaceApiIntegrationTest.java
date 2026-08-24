package com.specagent.api.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.common.Ids;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.readmodel.graph.GraphWorkspaceView;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Graph workspace read endpoint integration tests.
 *
 * <p>Proves the Phase 7.3 read-only graph surface: shared nodes are
 * deduplicated, route-specific answers stay separate, fork never copies
 * answers, regenerate replacement lineage never injects the superseded target,
 * every lifecycle status is inspectable, and corrupt lineage fails closed with
 * {@code INTERNAL_INVARIANT_VIOLATION} instead of a partial graph. Runs
 * against the default fake model gateway (zero public provider requests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GraphWorkspaceApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private com.specagent.answer.AnswerService answerService;
    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private DataSource dataSource;

    @Test
    void singleRouteReturnsCanonicalGraphWithAnswerPayload() throws Exception {
        Project project = projectService.createProject("Graph project");
        UUID routeId = project.activeRouteId();
        NodeOption option = NodeOption.of("Option A", "Impact A");
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                "Root purpose", List.of(option), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        com.specagent.answer.Answer answer = answerService.finalizeAnswer(project.id(), routeId,
                root.id(), option.id().toString(), "answer text", "user");

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.activeRouteId").value(routeId.toString()))
                .andExpect(jsonPath("$.routes[0].id").value(routeId.toString()))
                .andExpect(jsonPath("$.routes[0].lineageNodeIds[0]").value(root.id().toString()))
                .andExpect(jsonPath("$.routes[0].lineageNodeIds[1]").value(child.id().toString()))
                .andExpect(jsonPath("$.nodes[0].id").value(root.id().toString()))
                .andExpect(jsonPath("$.nodes[0].options[0].label").value("Option A"))
                .andExpect(jsonPath("$.nodes[1].id").value(child.id().toString()))
                .andExpect(jsonPath("$.answers[0].routeId").value(routeId.toString()))
                .andExpect(jsonPath("$.answers[0].nodeId").value(root.id().toString()))
                .andExpect(jsonPath("$.answers[0].selectedOptionId").value(option.id().toString()))
                .andExpect(jsonPath("$.answers[0].freeText").value("answer text"));
    }

    @Test
    void forkSharesNodesWithoutAnswerCopiesAndAnswersStayRouteSpecific() throws Exception {
        Project project = projectService.createProject("Fork graph project");
        UUID originalRouteId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), originalRouteId, "Root question",
                null, List.of(NodeOption.of("A", "a")), true);
        Node child = nodeService.createChildNode(project.id(), originalRouteId, root.id(),
                "Child question", null, List.of(), true);
        Node grandchild = nodeService.createChildNode(project.id(), originalRouteId, child.id(),
                "Grandchild question", null, List.of(), true);
        // Old route answers its child node.
        com.specagent.answer.Answer oldAnswer = answerService.finalizeAnswer(project.id(),
                originalRouteId, child.id(), null, "old route child answer", "user");

        // Fork from the child: shared history nodes are not copied, the fork
        // route points at the same immutable nodes, and no answer is copied.
        Route fork = routeService.forkFromNode(project.id(), originalRouteId, child.id(), "Fork from child");
        UUID forkRouteId = fork.id();
        assertThat(fork.tipNodeId()).isEqualTo(child.id());
        assertThat(nodeRepository.findByProject(project.id())).hasSize(3);
        // The fork route answers the same shared child node with its own answer.
        com.specagent.answer.Answer forkAnswer = answerService.finalizeAnswer(project.id(),
                forkRouteId, child.id(), null, "fork route child answer", "user");

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isOk())
                .andReturn();
        GraphWorkspaceView view = objectMapper.readValue(
                result.getResponse().getContentAsString(), GraphWorkspaceView.class);

        assertThat(view.routes()).hasSize(2);
        assertThat(view.routes()).extracting(r -> r.id())
                .containsExactly(originalRouteId, forkRouteId);
        // Shared nodes are rendered exactly once.
        assertThat(view.nodes()).extracting(n -> n.id())
                .containsExactly(root.id(), child.id(), grandchild.id());
        // Route membership is authoritative per route.
        assertThat(view.routes().get(0).lineageNodeIds())
                .containsExactly(root.id(), child.id(), grandchild.id());
        assertThat(view.routes().get(1).lineageNodeIds())
                .containsExactly(root.id(), child.id());
        // Both route-specific answers for the shared node stay separate.
        assertThat(view.answers()).hasSize(2);
        assertThat(view.answers()).filteredOn(a -> a.nodeId().equals(child.id()))
                .extracting(a -> a.routeId())
                .containsExactlyInAnyOrder(originalRouteId, forkRouteId);
        assertThat(view.answers()).extracting(a -> a.freeText())
                .containsExactlyInAnyOrder("old route child answer", "fork route child answer");
        assertThat(oldAnswer.routeId()).isNotEqualTo(forkAnswer.routeId());
    }

    @Test
    void replacementRouteLineageExcludesSupersededTargetSubtree() throws Exception {
        Project project = projectService.createProject("Regenerate graph project");
        UUID oldRouteId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), oldRouteId, "Root question",
                null, List.of(NodeOption.of("A", "a")), true);
        Node child = nodeService.createChildNode(project.id(), oldRouteId, root.id(),
                "Child question", null, List.of(), true);
        Node grandchild = nodeService.createChildNode(project.id(), oldRouteId, child.id(),
                "Grandchild question", null, List.of(), true);

        routeService.commitReplacementFromNode(project.id(), oldRouteId, child.id(), null,
                "Replacement question", "Replacement purpose", List.of(NodeOption.of("R", "r")), true);

        UUID replacementRouteId = projectService.getProject(project.id())
                .orElseThrow().activeRouteId();
        Route replacement = routeService.getRoute(replacementRouteId).orElseThrow();
        assertThat(replacement.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        GraphWorkspaceView view = objectMapper.readValue(body, GraphWorkspaceView.class);

        // Replacement route lineage = parent lineage + replacement node only.
        assertThat(view.routes()).extracting(r -> r.id())
                .containsExactly(oldRouteId, replacementRouteId);
        assertThat(view.routes().get(0).lifecycleStatus()).isEqualTo("superseded");
        assertThat(view.routes().get(1).lineageNodeIds())
                .containsExactly(root.id(), replacement.tipNodeId());
        assertThat(view.routes().get(1).replacementOfNodeId()).isEqualTo(child.id());
        // The superseded target and its old child subtree never appear in the
        // replacement route membership, and the old route keeps its subtree.
        assertThat(view.routes().get(0).lineageNodeIds())
                .containsExactly(root.id(), child.id(), grandchild.id());
        // The superseded target subtree is absent from the replacement route
        // membership even though the old route still carries it on the graph.
        assertThat(view.routes().get(1).lineageNodeIds())
                .doesNotContain(child.id(), grandchild.id());
        assertThat(view.nodes()).extracting(n -> n.question())
                .contains("Replacement question", "Grandchild question");
    }

    @Test
    void archivedRouteRemainsInspectableOnGraph() throws Exception {
        Project project = projectService.createProject("Archived graph project");
        UUID routeId = project.activeRouteId();
        nodeService.createRootNode(project.id(), routeId, "Archived question",
                null, List.of(), true);
        routeService.archiveRoute(project.id(), routeId);

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].id").value(routeId.toString()))
                .andExpect(jsonPath("$.routes[0].lifecycleStatus").value("archived"))
                .andExpect(jsonPath("$.routes[0].isActive").value(false))
                .andExpect(jsonPath("$.nodes[0].question").value("Archived question"));
    }

    @Test
    void staleRootDraftAgainstArchivedRouteReturnsConflictInsteadOf500() throws Exception {
        Project project = projectService.createProject("Stale graph command project");
        UUID routeId = project.activeRouteId();
        routeService.archiveRoute(project.id(), routeId);

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes", project.id())
                        .contentType("application/json")
                        .content("{\"routeId\":\"" + routeId
                                + "\",\"subtype\":\"NOTE\",\"content\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNTIME_CONFLICT"));
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void foreignNodeInGraphFailsClosedWithoutExposingForeignData() throws Exception {
        Project projectA = projectService.createProject("Owner A");
        Project projectB = projectService.createProject("Owner B");
        UUID routeA = projectA.activeRouteId();

        Node rootA = new Node(Ids.random(), projectA.id(), null, null, null,
                "A root question", null, List.of(), true, Instant.now());
        nodeRepository.save(rootA);
        Node foreignB = new Node(Ids.random(), projectB.id(), null, null, null,
                "FOREIGN_SENTINEL_GRAPH_1A2B", null, List.of(), true, Instant.now());
        nodeRepository.save(foreignB);
        Node corruptA = new Node(Ids.random(), projectA.id(), foreignB.id(), null, null,
                "A corrupt child question", null, List.of(), true, Instant.now());
        nodeRepository.save(corruptA);
        routeService.updateTip(routeA, corruptA.id(), rootA.id());

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/graph", projectA.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("FOREIGN_SENTINEL_GRAPH_1A2B");
        assertThat(body).doesNotContain("A corrupt child question");
    }

    @Test
    void missingNodeInGraphFailsClosed() throws Exception {
        Project project = projectService.createProject("Missing node graph project");
        UUID routeId = project.activeRouteId();
        Node root = new Node(Ids.random(), project.id(), null, null, null,
                "Root question", null, List.of(), true, Instant.now());
        nodeRepository.save(root);
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        jdbcTemplate.getJdbcOperations().execute("SET session_replication_role = replica");
        try {
            Node child = new Node(Ids.random(), project.id(), Ids.random(), null, null,
                    "Orphan child question", null, List.of(), true, Instant.now());
            nodeRepository.save(child);
            routeService.updateTip(routeId, child.id(), root.id());
        } finally {
            jdbcTemplate.getJdbcOperations().execute("SET session_replication_role = origin");
        }

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"));
    }

    @Test
    void cyclicLineageInGraphFailsClosed() throws Exception {
        Project project = projectService.createProject("Cycle graph project");
        UUID routeId = project.activeRouteId();
        UUID selfId = Ids.random();
        Node self = new Node(selfId, project.id(), selfId, null, null,
                "Self-referential question", null, List.of(), true, Instant.now());
        nodeRepository.save(self);
        routeService.updateTip(routeId, selfId, selfId);

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"));
    }

    @Test
    void rootMismatchInGraphFailsClosed() throws Exception {
        Project project = projectService.createProject("Root mismatch graph project");
        UUID routeId = project.activeRouteId();
        Node rootA = new Node(Ids.random(), project.id(), null, null, null,
                "Root A question", null, List.of(), true, Instant.now());
        nodeRepository.save(rootA);
        Node childA = new Node(Ids.random(), project.id(), rootA.id(), null, null,
                "Child of A", null, List.of(), true, Instant.now());
        nodeRepository.save(childA);
        routeService.updateTip(routeId, childA.id(), rootA.id());

        Node rootB = new Node(Ids.random(), project.id(), null, null, null,
                "Root B question", null, List.of(), true, Instant.now());
        nodeRepository.save(rootB);
        Node childB = new Node(Ids.random(), project.id(), rootB.id(), null, null,
                "Child of B", null, List.of(), true, Instant.now());
        nodeRepository.save(childB);
        routeService.updateTip(routeId, childB.id(), rootA.id());

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"));
    }
}
