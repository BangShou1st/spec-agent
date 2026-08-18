package com.specagent.api.spec;

import com.specagent.common.Ids;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.spec.SourceKind;
import com.specagent.spec.SourceReference;
import com.specagent.spec.SpecSection;
import com.specagent.spec.SpecSnapshot;
import com.specagent.spec.SpecSnapshotService;
import com.specagent.spec.UnresolvedItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec read API integration tests. Snapshots are exposed as derived artifacts
 * with provenance; project/route ownership is verified on scoped reads.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SpecApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private SpecSnapshotService specSnapshotService;
    @Autowired
    private RouteService routeService;

    private record SpecFixture(Project project, Route route, SpecSnapshot snapshot) {
    }

    private SpecFixture createProjectWithSnapshot() {
        Project project = projectService.createProject("Spec reading project");
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), "What are you clarifying?", null, List.of(), true);
        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(project.id(), null, ContextOperationType.NORMAL);
        SpecSnapshot snapshot = specSnapshotService.createSnapshot(
                project.id(),
                project.activeRouteId(),
                ctx.tipNodeId(),
                ctx.id(),
                "markdown",
                List.of(SpecSection.of("Goals", "A clear goal")),
                List.of(UnresolvedItem.of("Clarify scope", "open")),
                List.of(
                        SourceReference.of(SourceKind.NODE, root.id()),
                        SourceReference.of(SourceKind.ANSWER, Ids.random())),
                null);
        return new SpecFixture(project, routeService.getRoute(project.activeRouteId()).orElseThrow(), snapshot);
    }

    @Test
    void getExistingSnapshot() throws Exception {
        SpecFixture fixture = createProjectWithSnapshot();

        mockMvc.perform(get("/api/v1/specs/{snapshotId}", fixture.snapshot().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.snapshot().id().toString()))
                .andExpect(jsonPath("$.projectId").value(fixture.project().id().toString()))
                .andExpect(jsonPath("$.routeId").value(fixture.route().id().toString()))
                .andExpect(jsonPath("$.format").value("markdown"))
                .andExpect(jsonPath("$.sections", hasSize(1)))
                .andExpect(jsonPath("$.sections[0].title").value("Goals"))
                .andExpect(jsonPath("$.unresolvedItems", hasSize(1)))
                .andExpect(jsonPath("$.unresolvedItems[0].category").value("open"))
                .andExpect(jsonPath("$.sourceRefs", hasSize(2)))
                .andExpect(jsonPath("$.sourceRefs[0].kind").value("node"))
                .andExpect(jsonPath("$.createdByRunId").isEmpty())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void unknownSnapshotReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/specs/{snapshotId}", Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPEC_NOT_FOUND"));
    }

    @Test
    void listRouteSnapshots() throws Exception {
        SpecFixture fixture = createProjectWithSnapshot();
        // A second snapshot on the same route.
        specSnapshotService.createSnapshot(
                fixture.project().id(),
                fixture.route().id(),
                fixture.route().tipNodeId(),
                null,
                "markdown",
                List.of(SpecSection.of("Second", "Another section")),
                List.of(),
                List.of(),
                null);

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/specs",
                        fixture.project().id(), fixture.route().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].routeId").value(fixture.route().id().toString()));
    }

    @Test
    void routeFromAnotherProjectCannotBeReadThroughWrongProject() throws Exception {
        SpecFixture fixture = createProjectWithSnapshot();
        Project other = projectService.createProject("Other project");

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/specs",
                        other.id(), fixture.route().id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void unknownRouteInProjectReturnsNotFound() throws Exception {
        Project project = projectService.createProject("No route project");

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/specs",
                        project.id(), Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }
}