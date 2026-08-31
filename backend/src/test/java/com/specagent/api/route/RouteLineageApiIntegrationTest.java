package com.specagent.api.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.answer.AnswerService;
import com.specagent.common.Ids;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.readmodel.route.RouteLineageView;
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
 * Route-lineage read endpoint integration tests.
 *
 * <p>Proves the Phase 7.2 UI-support read is a safe, read-only, route-scoped
 * view of one route's historical node chain: never calls a model, never writes
 * state, inspects every lifecycle status, follows fork/regenerate semantics,
 * and fails closed on corrupt lineage so foreign data can never be exposed.
 * Runs against the default fake model gateway (zero public provider requests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RouteLineageApiIntegrationTest {

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
    private NodeRepository nodeRepository;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private DataSource dataSource;

    private record Chain(Project project, UUID routeId, Node root, Node child, Node grandchild) {
    }

    private Chain createRootChildGrandchild() {
        Project project = projectService.createProject("Lineage project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                "Root purpose", List.of(NodeOption.of("Option A", "Impact A")), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        Node grandchild = nodeService.createChildNode(project.id(), routeId, child.id(),
                "Grandchild question", null, List.of(), true);
        return new Chain(project, routeId, root, child, grandchild);
    }

    private MvcResult getLineage(UUID projectId, UUID routeId) throws Exception {
        return mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/lineage",
                        projectId, routeId))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void newRouteWithNoTipReturnsEmptyNodeList() throws Exception {
        Project project = projectService.createProject("Empty lineage project");

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/lineage",
                        project.id(), project.activeRouteId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.rootNodeId").isEmpty())
                .andExpect(jsonPath("$.tipNodeId").isEmpty())
                .andExpect(jsonPath("$.lifecycleStatus").value("open"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.nodes").isEmpty());
    }

    @Test
    void normalRouteReturnsExactRootToTipOrder() throws Exception {
        Chain chain = createRootChildGrandchild();

        MvcResult result = getLineage(chain.project().id(), chain.routeId());
        RouteLineageView view = objectMapper.readValue(
                result.getResponse().getContentAsString(), RouteLineageView.class);

        assertThat(view.rootNodeId()).isEqualTo(chain.root().id());
        assertThat(view.tipNodeId()).isEqualTo(chain.grandchild().id());
        assertThat(view.lifecycleStatus()).isEqualTo("open");
        assertThat(view.isActive()).isTrue();
        assertThat(view.nodes()).extracting(n -> n.id())
                .containsExactly(chain.root().id(), chain.child().id(), chain.grandchild().id());
        assertThat(view.nodes()).extracting(n -> n.question())
                .containsExactly("Root question", "Child question", "Grandchild question");
        assertThat(view.nodes().get(0).parentNodeId()).isNull();
        assertThat(view.nodes().get(1).parentNodeId()).isEqualTo(chain.root().id());
        assertThat(view.nodes().get(2).parentNodeId()).isEqualTo(chain.child().id());
        assertThat(view.nodes().get(0).purpose()).isEqualTo("Root purpose");
        assertThat(view.nodes().get(0).options()).extracting(o -> o.label())
                .containsExactly("Option A");
        assertThat(view.nodes().get(0).allowFreeAnswer()).isTrue();
        assertThat(view.nodes().get(0).createdAt()).isNotNull();
    }

    @Test
    void routeFromAnotherProjectIsNotFound() throws Exception {
        Project projectA = projectService.createProject("Owner A");
        Project projectB = projectService.createProject("Owner B");

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/lineage",
                        projectA.id(), projectB.activeRouteId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        Project project = projectService.createProject("Unknown route project");

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/lineage",
                        project.id(), Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void unknownProjectIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/lineage",
                        Ids.random(), Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void supersededRouteRemainsInspectable() throws Exception {
        Chain chain = createRootChildGrandchild();
        routeService.commitReplacementFromNode(chain.project().id(), chain.routeId(), chain.child().id(),
                chain.grandchild().id(), null, "Replacement question", "Replacement purpose", List.of(), true);
        Route oldRoute = routeService.getRoute(chain.routeId()).orElseThrow();
        assertThat(oldRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);

        MvcResult result = getLineage(chain.project().id(), chain.routeId());
        RouteLineageView view = objectMapper.readValue(
                result.getResponse().getContentAsString(), RouteLineageView.class);
        assertThat(view.lifecycleStatus()).isEqualTo("superseded");
        assertThat(view.isActive()).isFalse();
        assertThat(view.nodes()).extracting(n -> n.id())
                .containsExactly(chain.root().id(), chain.child().id(), chain.grandchild().id());
    }

    @Test
    void archivedRouteRemainsInspectable() throws Exception {
        Chain chain = createRootChildGrandchild();
        routeService.archiveRoute(chain.project().id(), chain.routeId());

        MvcResult result = getLineage(chain.project().id(), chain.routeId());
        RouteLineageView view = objectMapper.readValue(
                result.getResponse().getContentAsString(), RouteLineageView.class);
        assertThat(view.lifecycleStatus()).isEqualTo("archived");
        assertThat(view.isActive()).isFalse();
        assertThat(view.nodes()).extracting(n -> n.id())
                .containsExactly(chain.root().id(), chain.child().id(), chain.grandchild().id());
    }

    @Test
    void deletedRouteRemainsInspectable() throws Exception {
        Chain chain = createRootChildGrandchild();
        routeService.softDeleteRoute(chain.project().id(), chain.routeId());

        MvcResult result = getLineage(chain.project().id(), chain.routeId());
        RouteLineageView view = objectMapper.readValue(
                result.getResponse().getContentAsString(), RouteLineageView.class);
        assertThat(view.lifecycleStatus()).isEqualTo("deleted");
        assertThat(view.nodes()).extracting(n -> n.id())
                .containsExactly(chain.root().id(), chain.child().id(), chain.grandchild().id());
    }

    @Test
    void forkRouteSharesImmutableNodesWithoutCopies() throws Exception {
        Chain chain = createRootChildGrandchild();
        UUID originalRouteId = chain.routeId();
        int nodeCountBefore = nodeRepository.findByProject(chain.project().id()).size();
        answerService.finalizeAnswer(chain.project().id(), chain.routeId(), chain.child().id(),
                null, "Child answer", "user");

        // Fork from the child node through the real command API.
        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork",
                        chain.project().id(), chain.child().id())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"sourceRouteId\": \"" + chain.routeId() + "\"}"))
                .andExpect(status().isOk());

        UUID forkRouteId = projectService.getProject(chain.project().id())
                .orElseThrow().activeRouteId();
        Route fork = routeService.getRoute(forkRouteId).orElseThrow();
        assertThat(fork.tipNodeId()).isEqualTo(chain.child().id());

        // No node copies; the same immutable nodes are shared.
        assertThat(nodeRepository.findByProject(chain.project().id())).hasSize(nodeCountBefore);

        MvcResult result = getLineage(chain.project().id(), forkRouteId);
        RouteLineageView view = objectMapper.readValue(
                result.getResponse().getContentAsString(), RouteLineageView.class);
        assertThat(view.nodes()).extracting(n -> n.id())
                .containsExactly(chain.root().id(), chain.child().id());
        assertThat(view.rootNodeId()).isEqualTo(chain.root().id());
        assertThat(view.tipNodeId()).isEqualTo(chain.child().id());

        // The old route lineage is untouched and still root??ip complete.
        RouteLineageView oldView = objectMapper.readValue(
                getLineage(chain.project().id(), originalRouteId)
                        .getResponse().getContentAsString(), RouteLineageView.class);
        assertThat(oldView.nodes()).extracting(n -> n.id())
                .containsExactly(chain.root().id(), chain.child().id(), chain.grandchild().id());
    }

    @Test
    void replacementRouteContainsParentLineagePlusReplacementNodeOnly() throws Exception {
        Chain chain = createRootChildGrandchild();

        routeService.commitReplacementFromNode(chain.project().id(), chain.routeId(), chain.child().id(),
                chain.grandchild().id(), null, "Replacement scope question", "Replacement purpose",
                List.of(NodeOption.of("Replacement option", "Impact")), true);

        UUID replacementRouteId = projectService.getProject(chain.project().id())
                .orElseThrow().activeRouteId();
        Route replacement = routeService.getRoute(replacementRouteId).orElseThrow();
        assertThat(replacement.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);

        MvcResult result = getLineage(chain.project().id(), replacementRouteId);
        RouteLineageView view = objectMapper.readValue(
                result.getResponse().getContentAsString(), RouteLineageView.class);
        assertThat(view.nodes()).extracting(n -> n.id())
                .containsExactly(chain.root().id(), replacement.tipNodeId());
        assertThat(view.nodes().get(1).question()).isEqualTo("Replacement scope question");
        assertThat(view.nodes().get(1).purpose()).isEqualTo("Replacement purpose");
        assertThat(view.nodes().get(1).supersedesNodeId()).isEqualTo(chain.child().id());
        assertThat(view.nodes().get(1).options()).extracting(o -> o.label())
                .containsExactly("Replacement option");
        // The superseded target node and its old child subtree are absent.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Child question");
        assertThat(body).doesNotContain("Grandchild question");

        // The old route still carries the full old subtree.
        RouteLineageView oldView = objectMapper.readValue(
                getLineage(chain.project().id(), chain.routeId())
                        .getResponse().getContentAsString(), RouteLineageView.class);
        assertThat(oldView.nodes()).extracting(n -> n.id())
                .containsExactly(chain.root().id(), chain.child().id(), chain.grandchild().id());
    }

    @Test
    void foreignNodeInLineageFailsClosedWithoutExposingForeignData() throws Exception {
        Project projectA = projectService.createProject("Owner A");
        Project projectB = projectService.createProject("Owner B");
        UUID routeA = projectA.activeRouteId();

        Node rootA = new Node(Ids.random(), projectA.id(), null, null, null,
                "A root question", null, List.of(), true, Instant.now());
        nodeRepository.save(rootA);
        Node foreignB = new Node(Ids.random(), projectB.id(), null, null, null,
                "FOREIGN_SENTINEL_LINEAGE_5C21", null, List.of(), true, Instant.now());
        nodeRepository.save(foreignB);
        // A's node points at a node owned by project B.
        Node corruptA = new Node(Ids.random(), projectA.id(),
                foreignB.id(), null, null, "A corrupt child question", null,
                List.of(), true, Instant.now());
        nodeRepository.save(corruptA);
        routeService.updateTip(routeA, corruptA.id(), rootA.id());

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/lineage",
                        projectA.id(), routeA))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("FOREIGN_SENTINEL_LINEAGE_5C21");
        assertThat(body).doesNotContain("A corrupt child question");
    }

    @Test
    void missingNodeInLineageFailsClosed() throws Exception {
        Project project = projectService.createProject("Missing node project");
        UUID routeId = project.activeRouteId();
        Node root = new Node(Ids.random(), project.id(), null, null, null,
                "Root question", null, List.of(), true, Instant.now());
        nodeRepository.save(root);
        // The nodes table enforces parent_node_id referential integrity, so a
        // genuinely dangling parent cannot be written normally. Bypass FK
        // checks for this single insert to simulate the corrupt state the
        // read must fail closed on.
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        jdbcTemplate.getJdbcOperations().execute("SET session_replication_role = replica");
        try {
            Node child = new Node(Ids.random(), project.id(),
                    Ids.random(), null, null, "Orphan child question", null,
                    List.of(), true, Instant.now());
            nodeRepository.save(child);
            routeService.updateTip(routeId, child.id(), root.id());
        } finally {
            jdbcTemplate.getJdbcOperations().execute("SET session_replication_role = origin");
        }

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/lineage",
                        project.id(), routeId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"));
    }

    @Test
    void cyclicLineageFailsClosed() throws Exception {
        Project project = projectService.createProject("Cycle project");
        UUID routeId = project.activeRouteId();
        UUID selfId = Ids.random();
        Node self = new Node(selfId, project.id(), selfId, null, null,
                "Self-referential question", null, List.of(), true, Instant.now());
        nodeRepository.save(self);
        routeService.updateTip(routeId, selfId, selfId);

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/lineage",
                        project.id(), routeId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"));
    }

    @Test
    void rootMismatchFailsClosed() throws Exception {
        Project project = projectService.createProject("Root mismatch project");
        UUID routeId = project.activeRouteId();
        Node rootA = new Node(Ids.random(), project.id(), null, null, null,
                "Root A question", null, List.of(), true, Instant.now());
        nodeRepository.save(rootA);
        Node childA = new Node(Ids.random(), project.id(), rootA.id(),
                null, null, "Child of A", null, List.of(), true, Instant.now());
        nodeRepository.save(childA);
        routeService.updateTip(routeId, childA.id(), rootA.id());

        // A second root that is NOT the lineage root; route root stays rootA.
        Node rootB = new Node(Ids.random(), project.id(), null, null, null,
                "Root B question", null, List.of(), true, Instant.now());
        nodeRepository.save(rootB);
        Node childB = new Node(Ids.random(), project.id(), rootB.id(),
                null, null, "Child of B", null, List.of(), true, Instant.now());
        nodeRepository.save(childB);
        routeService.updateTip(routeId, childB.id(), rootA.id());

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/lineage",
                        project.id(), routeId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"));
    }
}
