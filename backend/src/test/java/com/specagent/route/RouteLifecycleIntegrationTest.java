package com.specagent.route;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RouteLifecycleIntegrationTest {

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
    private ContextBuilder contextBuilder;

    private record Fixture(Project project, UUID routeId, Node root, Answer answer, AnswerPatch patch) {
    }

    private Fixture createProjectWithData() {
        Project project = projectService.createProject("Route lifecycle project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "What are you clarifying?",
                null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(project.id(), routeId, root.id(), null,
                "A vague idea", "user");
        Claim claim = Claim.of(ClaimKind.GOAL, "Clarify the idea", ClaimStatus.CONFIRMED,
                root.id(), answer.id());
        AnswerPatch patch = answerPatchService.save(project.id(), routeId, root.id(), answer.id(),
                List.of(claim), null);
        return new Fixture(project, routeId, root, answer, patch);
    }

    @Test
    void setActiveRouteRejectsDeletedRoute() {
        Fixture f = createProjectWithData();
        Route other = routeService.createRoute(f.project().id(), RouteLifecycleStatus.DELETED, "deleted");

        assertThatThrownBy(() -> routeService.setActiveRoute(f.project().id(), other.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setActiveRouteRejectsArchivedRoute() {
        Fixture f = createProjectWithData();
        Route other = routeService.createRoute(f.project().id(), RouteLifecycleStatus.ARCHIVED, "archived");

        assertThatThrownBy(() -> routeService.setActiveRoute(f.project().id(), other.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setActiveRouteRejectsSupersededRoute() {
        Fixture f = createProjectWithData();
        Route other = routeService.createRoute(f.project().id(), RouteLifecycleStatus.SUPERSEDED, "superseded");

        assertThatThrownBy(() -> routeService.setActiveRoute(f.project().id(), other.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setActiveRouteRejectsRouteFromAnotherProject() {
        Fixture f1 = createProjectWithData();
        Fixture f2 = createProjectWithData();

        assertThatThrownBy(() -> routeService.setActiveRoute(f1.project().id(), f2.routeId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void archiveActiveRouteClearsActiveRoute() {
        Fixture f = createProjectWithData();

        routeService.archiveRoute(f.project().id(), f.routeId());

        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId()).isNull();
        Route route = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.ARCHIVED);
    }

    /**
     * Archiving the active route clears the active pointer even when another OPEN
     * route exists. RouteService must NOT auto-select the other OPEN route — the
     * caller decides the next active route explicitly. This locks the no-implicit-
     * selection contract and the active-route invariant.
     */
    @Test
    void archiveActiveRouteClearsPointerAndDoesNotAutoSelectAnotherOpenRoute() {
        Fixture f = createProjectWithData();
        Route other = routeService.createRoute(f.project().id(), RouteLifecycleStatus.OPEN, "另一条 OPEN 路线");

        routeService.archiveRoute(f.project().id(), f.routeId());

        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId())
                .as("archiving the active route must clear the pointer, not auto-pick another OPEN route")
                .isNull();
        Route archived = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(archived.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.ARCHIVED);
        Route stillOpen = routeService.getRoute(other.id()).orElseThrow();
        assertThat(stillOpen.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
    }

    @Test
    void softDeleteActiveRouteClearsActiveRoute() {
        Fixture f = createProjectWithData();

        routeService.softDeleteRoute(f.project().id(), f.routeId());

        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId()).isNull();
        Route route = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.DELETED);
    }

    @Test
    void restoreRouteReopensAndActivatesRoute() {
        Fixture f = createProjectWithData();
        routeService.archiveRoute(f.project().id(), f.routeId());
        assertThat(projectService.getProject(f.project().id()).orElseThrow().activeRouteId()).isNull();

        routeService.restoreRoute(f.project().id(), f.routeId());

        Route route = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId()).isEqualTo(f.routeId());
    }

    @Test
    void softDeleteDoesNotDeleteNodesAnswersOrPatches() {
        Fixture f = createProjectWithData();

        routeService.softDeleteRoute(f.project().id(), f.routeId());

        assertThat(nodeService.getNode(f.root().id())).isPresent();
        assertThat(answerService.getAnswer(f.answer().id())).isPresent();
        List<AnswerPatch> patches = answerPatchService.findByRoute(f.routeId());
        assertThat(patches).extracting(AnswerPatch::id).contains(f.patch().id());
    }

    @Test
    void contextBuilderRejectsDeletedActiveRoute() {
        Fixture f = createProjectWithData();
        routeService.softDeleteRoute(f.project().id(), f.routeId());
        forceActiveRoute(f.project().id(), f.routeId());

        assertThatThrownBy(() -> contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.NORMAL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void contextBuilderRejectsArchivedActiveRoute() {
        Fixture f = createProjectWithData();
        routeService.archiveRoute(f.project().id(), f.routeId());
        forceActiveRoute(f.project().id(), f.routeId());

        assertThatThrownBy(() -> contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.NORMAL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void contextBuilderRejectsSupersededActiveRoute() {
        // Create a superseded route by performing a regenerate operation.
        Fixture f = createProjectWithData();
        // Need a child node to regenerate from; create one.
        Node child = nodeService.createChildNode(f.project().id(), f.routeId(), f.root().id(),
                "Child question", null, List.of(), true);
        // Regenerate to make the original route SUPERSEDED.
        RegenerateResult result = routeService.commitReplacementFromNode(
                f.project().id(), f.routeId(), child.id(), child.id(), null,
                "New question", "New purpose", List.of(), true);

        // The old route is now SUPERSEDED.
        Route supersededRoute = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(supersededRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);

        // Force the superseded route to be active (simulating invalid state).
        forceActiveRoute(f.project().id(), f.routeId());

        // ContextBuilder should reject it.
        assertThatThrownBy(() -> contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.NORMAL))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Directly repoints the active route pointer to a non-open route. This
     * simulates an invalid project state that ContextBuilder must reject.
     * RouteService.setActiveRoute cannot be used here because it correctly
     * refuses non-open routes.
     */
    private void forceActiveRoute(UUID projectId, UUID routeId) {
        projectRepository.updateActiveRoute(projectId, routeId, Instant.now());
    }
}
