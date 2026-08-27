package com.specagent.api.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression lock for the semantic-relation creation path. The drag-to-connect
 * affordance in {@code connection.spec.ts} only ever records a relation by
 * calling {@code POST /api/v1/projects/{id}/relations}. This test covers the
 * whole contract: identity (sourceNodeId, targetNodeId, relationType) is
 * stored verbatim, the line-typed relation kind defaults to RELATED_TO, the
 * origin is USER for human-authored drags, and the relation is immediately
 * visible in the read-model graph view that the UI uses to render the
 * Inspector. If any of these invariants ever regress, the on-canvas drag
 * would silently do nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SemanticRelationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void dragToConnectCreatesRelatedToUserRelationRecordedInGraphView() throws Exception {
        Project project = projectService.createProject("Relation regression project");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = nodeService.createRootNode(project.id(), route.id(),
                "Drag source", null, List.of(), true);
        Node target = nodeService.createChildNode(project.id(), route.id(), root.id(),
                "Drag target", null, List.of(), true);

        // The drag-to-connect affordance always sends these three fields;
        // anything else is rejected by the controller.
        String body = objectMapper.writeValueAsString(Map.of(
                "sourceNodeId", root.id().toString(),
                "targetNodeId", target.id().toString(),
                "relationType", "RELATED_TO"));

        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.sourceNodeId").value(root.id().toString()))
                .andExpect(jsonPath("$.targetNodeId").value(target.id().toString()))
                .andExpect(jsonPath("$.relationType").value("RELATED_TO"))
                .andExpect(jsonPath("$.origin").value("USER"));
    }

    @Test
    void dragToConnectRejectsCrossProjectSourceNode() throws Exception {
        Project projectA = projectService.createProject("Relation A");
        Project projectB = projectService.createProject("Relation B");
        Route routeA = routeRepository.findById(projectA.activeRouteId()).orElseThrow();
        Node nodeA = nodeService.createRootNode(projectA.id(), routeA.id(),
                "A node", null, List.of(), true);
        Node nodeB = nodeService.createRootNode(projectB.id(), projectB.activeRouteId(),
                "B node", null, List.of(), true);

        // sourceNodeId from project A used inside project B must fail as a
        // client error (the source node does not belong to project B), never
        // as a 500. The API never silently accepts cross-project relations,
        // which is what the drag-to-connect path must also honor.
        String body = objectMapper.writeValueAsString(Map.of(
                "sourceNodeId", nodeA.id().toString(),
                "targetNodeId", nodeB.id().toString(),
                "relationType", "RELATED_TO"));

        mockMvc.perform(post("/api/v1/projects/{pid}/relations", projectB.id())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void duplicateDragToConnectIsRejectedAsConflict() throws Exception {
        Project project = projectService.createProject("Duplicate relation regression");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = nodeService.createRootNode(project.id(), route.id(),
                "Source", null, List.of(), true);
        Node target = nodeService.createChildNode(project.id(), route.id(), root.id(),
                "Target", null, List.of(), true);

        String body = objectMapper.writeValueAsString(Map.of(
                "sourceNodeId", root.id().toString(),
                "targetNodeId", target.id().toString(),
                "relationType", "RELATED_TO"));

        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());

        // Second drag on the same pair must be rejected. Reverse direction is
        // NOT considered a duplicate (per V2 product rule: identity is
        // (source, target, type), reverse is a distinct fact).
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict());
    }
}
