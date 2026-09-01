package com.specagent.runtime;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.agent.AgentRunService;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.context.RequirementState;
import com.specagent.context.RequirementStateBuilder;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.profile.ProfileService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import com.specagent.spec.SourceKind;
import com.specagent.spec.SourceReference;
import com.specagent.spec.SpecSection;
import com.specagent.spec.SpecSnapshot;
import com.specagent.spec.SpecSnapshotService;
import com.specagent.spec.UnresolvedItem;
import com.specagent.common.Ids;
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
class RuntimeKernelIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private RequirementStateBuilder requirementStateBuilder;
    @Autowired
    private SpecSnapshotService specSnapshotService;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private ProfileService profileService;

    private record ProjectSetup(
            Project project,
            UUID routeId,
            Node root,
            Node child,
            Answer a1,
            Answer a2,
            AnswerPatch p1,
            AnswerPatch p2) {
    }

    private ProjectSetup setupBasicProject() {
        Project project = projectService.createProject("Demo requirement");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "What are you clarifying?", null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(), "Who is the first user?", null, List.of(), true);

        Answer a1 = answerService.finalizeAnswer(project.id(), routeId, root.id(), null, "An app idea", "user");
        Answer a2 = answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "Independent developer", "user");

        Claim c1 = Claim.of(ClaimKind.GOAL, "Build an app", ClaimStatus.CONFIRMED, root.id(), a1.id());
        Claim c2 = Claim.of(ClaimKind.CONSTRAINT, "Single developer", ClaimStatus.ASSUMED, child.id(), a2.id());
        AnswerPatch p1 = answerPatchService.save(project.id(), routeId, root.id(), a1.id(), List.of(c1), null);
        AnswerPatch p2 = answerPatchService.save(project.id(), routeId, child.id(), a2.id(), List.of(c2), null);

        return new ProjectSetup(project, routeId, root, child, a1, a2, p1, p2);
    }

    @Test
    void createProjectWithActiveRoute() {
        Project project = projectService.createProject("Photo planning tool");

        assertThat(project.activeRouteId()).isNotNull();
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(route.rootNodeId()).isNull();
        assertThat(project.defaultProfileId()).isEqualTo(profileService.getDefaultProfileId());
    }

    @Test
    void createRootNodeAndChildNode() {
        ProjectSetup s = setupBasicProject();

        Route route = routeService.getRoute(s.routeId()).orElseThrow();
        assertThat(route.rootNodeId()).isEqualTo(s.root().id());
        assertThat(route.tipNodeId()).isEqualTo(s.child().id());

        Node reread = nodeService.getNode(s.root().id()).orElseThrow();
        assertThat(reread.question()).isEqualTo("What are you clarifying?");
        assertThat(reread.isRoot()).isTrue();
    }

    @Test
    void buildContextFromActiveRouteLineage() {
        ProjectSetup s = setupBasicProject();

        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(s.project().id(), null, ContextOperationType.NORMAL);

        assertThat(ctx.routeId()).isEqualTo(s.routeId());
        assertThat(ctx.tipNodeId()).isEqualTo(s.child().id());
        assertThat(ctx.includedNodeIds()).containsExactly(s.root().id(), s.child().id());
        assertThat(ctx.includedAnswerIds()).containsExactly(s.a1().id(), s.a2().id());
        assertThat(ctx.includedPatchIds()).containsExactly(s.p1().id(), s.p2().id());
        assertThat(ctx.contextHash()).isNotBlank();
    }

    @Test
    void replayAnswerPatchesIntoRequirementState() {
        ProjectSetup s = setupBasicProject();

        RequirementState state = requirementStateBuilder.buildForRoute(s.project().id(), s.routeId());

        assertThat(state.isEmpty()).isFalse();
        assertThat(state.claims()).hasSize(2);
        assertThat(state.confirmed()).hasSize(1);
        assertThat(state.unresolved()).hasSize(1);
    }

    @Test
    void buildForContextReplaysPatchesInDeclaredOrder() {
        ProjectSetup s = setupBasicProject();
        // p1 carries c1 ("Build an app"), p2 carries c2 ("Single developer").
        // A context snapshot whose patch list is reversed must replay p2 before p1.
        ContextSnapshot reversedPatchOrder = new ContextSnapshot(
                Ids.random(),
                s.project().id(),
                s.routeId(),
                s.child().id(),
                ContextOperationType.NORMAL,
                List.of(s.root().id(), s.child().id()),
                List.of(s.a1().id(), s.a2().id()),
                List.of(s.p2().id(), s.p1().id()),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                Instant.now());

        RequirementState state = requirementStateBuilder.buildForContext(reversedPatchOrder);

        assertThat(state.claims()).hasSize(2);
        // Order is driven by includedPatchIds, not by includedAnswerIds (which is [a1, a2]).
        assertThat(state.claims().get(0).text()).isEqualTo("Single developer");
        assertThat(state.claims().get(1).text()).isEqualTo("Build an app");
    }

    @Test
    void excludeSiblingRoutes() {
        ProjectSetup s = setupBasicProject();

        Route sibling = routeService.createRoute(s.project().id(), RouteLifecycleStatus.OPEN, "sibling route");
        Node siblingNode = nodeService.createRootNode(s.project().id(), sibling.id(), "Sibling question", null, List.of(), true);

        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(s.project().id(), null, ContextOperationType.NORMAL);

        assertThat(ctx.includedNodeIds()).doesNotContain(siblingNode.id());
        assertThat(ctx.excludedRouteIds()).contains(sibling.id());
    }

    @Test
    void excludeDeletedRoutes() {
        ProjectSetup s = setupBasicProject();

        Route deleted = routeService.createRoute(s.project().id(), RouteLifecycleStatus.DELETED, "deleted route");
        Node deletedNode = nodeService.createRootNode(s.project().id(), deleted.id(), "Deleted question", null, List.of(), true);

        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(s.project().id(), null, ContextOperationType.NORMAL);

        assertThat(ctx.includedNodeIds()).doesNotContain(deletedNode.id());
        assertThat(ctx.excludedRouteIds()).contains(deleted.id());
    }

    @Test
    void excludeSupersededRoutesByDefault() {
        ProjectSetup s = setupBasicProject();

        Route superseded = routeService.createRoute(s.project().id(), RouteLifecycleStatus.SUPERSEDED, "superseded route");
        Node supersededNode = nodeService.createRootNode(s.project().id(), superseded.id(), "Superseded question", null, List.of(), true);

        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(s.project().id(), null, ContextOperationType.NORMAL);

        assertThat(ctx.includedNodeIds()).doesNotContain(supersededNode.id());
        assertThat(ctx.excludedRouteIds()).contains(superseded.id());
    }

    @Test
    void preventAnswerOverwriteAfterFinalization() {
        ProjectSetup s = setupBasicProject();

        assertThatThrownBy(() -> answerService.finalizeAnswer(
                s.project().id(), s.routeId(), s.root().id(), null, "attempted overwrite", "user"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void specSnapshotCarriesRouteTipAndSourceReferences() {
        ProjectSetup s = setupBasicProject();
        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(s.project().id(), null, ContextOperationType.NORMAL);

        SpecSnapshot snapshot = specSnapshotService.createSnapshot(
                s.project().id(),
                s.routeId(),
                ctx.tipNodeId(),
                ctx.id(),
                "markdown",
                List.of(SpecSection.of("Goals", "A clear app goal")),
                List.of(UnresolvedItem.of("Clarify scope", "open")),
                List.of(
                        SourceReference.of(SourceKind.NODE, s.root().id()),
                        SourceReference.of(SourceKind.ANSWER, s.a1().id())),
                null);

        SpecSnapshot loaded = specSnapshotService.getSnapshot(snapshot.id()).orElseThrow();
        assertThat(loaded.routeId()).isEqualTo(s.routeId());
        assertThat(loaded.tipNodeId()).isEqualTo(ctx.tipNodeId());
        assertThat(loaded.hasSourceReferences()).isTrue();
        assertThat(loaded.sourceRefs()).hasSize(2);
        assertThat(loaded.sourceRefs().stream().map(SourceReference::kind))
                .containsExactlyInAnyOrder(SourceKind.NODE, SourceKind.ANSWER);
        assertThat(loaded.sections()).hasSize(1);
        assertThat(loaded.unresolvedItems()).hasSize(1);
    }

    @Test
    void agentRunCanBeRecordedForContext() {
        ProjectSetup s = setupBasicProject();

        var run = agentRunService.create(s.project().id(), s.routeId(),
                com.specagent.agent.AgentRunTriggerType.ANSWER_NODE, s.child().id(), null);
        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(s.project().id(), run.id(), ContextOperationType.NORMAL);

        assertThat(ctx.routeId()).isEqualTo(s.routeId());
    }
}
