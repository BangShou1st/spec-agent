package com.specagent.route;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RouteControlIntegrationTest {

    @Autowired
    private ProjectService projectService;
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
    @Autowired
    private RouteInheritedAnswerRepository inheritedAnswerRepository;

    private record Fixture(Project project, UUID routeId, Node root, Node child,
                           Answer a1, Answer a2, AnswerPatch p1, AnswerPatch p2) {
    }

    private Fixture createFixture() {
        Project project = projectService.createProject("Route control project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "What are you clarifying?",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Who is the first user?", null, List.of(), true);
        Answer a1 = answerService.finalizeAnswer(project.id(), routeId, root.id(), null,
                "An app idea", "user");
        Answer a2 = answerService.finalizeAnswer(project.id(), routeId, child.id(), null,
                "Independent developer", "user");
        Claim c1 = Claim.of(ClaimKind.GOAL, "Build an app", ClaimStatus.CONFIRMED, root.id(), a1.id());
        Claim c2 = Claim.of(ClaimKind.CONSTRAINT, "Single developer", ClaimStatus.ASSUMED, child.id(), a2.id());
        AnswerPatch p1 = answerPatchService.save(project.id(), routeId, root.id(), a1.id(), List.of(c1), null);
        AnswerPatch p2 = answerPatchService.save(project.id(), routeId, child.id(), a2.id(), List.of(c2), null);
        return new Fixture(project, routeId, root, child, a1, a2, p1, p2);
    }

    /**
     * Topology-focused tests call the modern deterministic commit boundary
     * directly. Context-focused tests add the separate runtime context build
     * that the production replacement cycle performs after the commit.
     */
    private RegenerateResult commitReplacement(UUID projectId,
                                               UUID sourceRouteId,
                                               UUID targetNodeId,
                                               UUID expectedSourceRouteTip,
                                               String label,
                                               String question,
                                               String purpose,
                                               List<NodeOption> options) {
        return routeService.commitReplacementFromNode(
                projectId, sourceRouteId, targetNodeId, expectedSourceRouteTip, label,
                question, purpose, options, true);
    }

    private RegenerateResult replacementWithContext(Fixture fixture,
                                                    String instruction,
                                                    String question,
                                                    String purpose,
                                                    List<NodeOption> options) {
        // The fixture's route tip is the child node; the replacement freezes it
        // so a concurrent continuation can never advance it mid-commit.
        RegenerateResult committed = commitReplacement(
                fixture.project().id(), fixture.routeId(), fixture.child().id(),
                fixture.child().id(), null, question, purpose, options);
        ContextSnapshot context = contextBuilder.buildForRegenerate(
                fixture.project().id(), fixture.routeId(), fixture.child().id(),
                committed.replacementRoute().id(), committed.replacementNode().id(),
                instruction);
        return new RegenerateResult(
                committed.oldRoute(), committed.replacementRoute(),
                committed.replacementNode(), context);
    }

    @Test
    void forkFromNodeCreatesOpenRouteAtHistoricalNode() {
        Fixture f = createFixture();
        Route fork = routeService.forkFromNode(f.project().id(), f.routeId(), f.root().id(), "Fork at root");
        assertThat(fork.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(fork.rootNodeId()).isEqualTo(f.root().id());
        assertThat(fork.tipNodeId()).isEqualTo(f.root().id());
        assertThat(fork.createdFromNodeId()).isEqualTo(f.root().id());
        assertThat(fork.label()).isEqualTo("Fork at root");
    }

    @Test
    void explicitForkFreezesEffectivePrefixWithoutCloningAnswers() {
        Fixture f = createFixture();

        Route fork = routeService.forkFromNode(
                f.project().id(), f.routeId(), f.child().id(), "Explicit fork");

        assertThat(fork.branchType()).isEqualTo(RouteBranchType.FORK);
        assertThat(fork.sourceRouteId()).isEqualTo(f.routeId());
        assertThat(fork.branchAtNodeId()).isEqualTo(f.child().id());
        assertThat(inheritedAnswerRepository.findByBranchRouteId(fork.id()))
                .extracting(RouteInheritedAnswer::answerId)
                .containsExactly(f.a1().id(), f.a2().id());
        assertThat(answerService.getAnswer(f.a1().id()).orElseThrow().routeId())
                .isEqualTo(f.routeId());

        routeService.archiveRoute(f.project().id(), f.routeId());
        assertThat(inheritedAnswerRepository.findByBranchRouteId(fork.id()))
                .extracting(RouteInheritedAnswer::answerId)
                .containsExactly(f.a1().id(), f.a2().id());
    }

    @Test
    void chainedExplicitForkUsesEffectiveInheritedHistory() {
        Fixture f = createFixture();
        Route first = routeService.forkFromNode(
                f.project().id(), f.routeId(), f.child().id(), "First fork");
        Route second = routeService.forkFromNode(
                f.project().id(), first.id(), f.child().id(), "Second fork");

        assertThat(inheritedAnswerRepository.findByBranchRouteId(second.id()))
                .extracting(RouteInheritedAnswer::answerId)
                .containsExactly(f.a1().id(), f.a2().id());
    }

    @Test
    void forkFromNodeSetsNewRouteActive() {
        Fixture f = createFixture();
        Route fork = routeService.forkFromNode(f.project().id(), f.routeId(), f.root().id(), "Fork at root");
        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId()).isEqualTo(fork.id());
    }

    @Test
    void forkFromNodeDoesNotModifyOldRoute() {
        Fixture f = createFixture();
        UUID oldRouteId = f.routeId();
        routeService.forkFromNode(f.project().id(), f.routeId(), f.root().id(), "Fork at root");
        Route oldRoute = routeService.getRoute(oldRouteId).orElseThrow();
        assertThat(oldRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(oldRoute.tipNodeId()).isEqualTo(f.child().id());
        assertThat(oldRoute.createdFromNodeId()).isNull();
    }

    @Test
    void forkFromNodeInheritsOnlySelectedLineage() {
        Fixture f = createFixture();
        Route fork = routeService.forkFromNode(f.project().id(), f.routeId(), f.root().id(), "Fork at root");
        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.FORK);
        assertThat(ctx.routeId()).isEqualTo(fork.id());
        assertThat(ctx.tipNodeId()).isEqualTo(f.root().id());
        assertThat(ctx.includedNodeIds()).containsExactly(f.root().id());
        assertThat(ctx.includedAnswerIds()).containsExactly(f.a1().id());
        assertThat(ctx.includedPatchIds()).containsExactly(f.p1().id());
    }

    @Test
    void forkFromNodeDoesNotIncludeSiblingRouteAnswersOrPatches() {
        Fixture f = createFixture();
        Route sibling = routeService.createRoute(f.project().id(), RouteLifecycleStatus.OPEN, "Sibling");
        Node siblingRoot = nodeService.createRootNode(f.project().id(), sibling.id(), "Sibling question",
                null, List.of(), true);
        Answer siblingAnswer = answerService.finalizeAnswer(f.project().id(), sibling.id(), siblingRoot.id(),
                null, "Sibling answer", "user");
        answerPatchService.save(f.project().id(), sibling.id(), siblingRoot.id(), siblingAnswer.id(),
                List.of(Claim.of(ClaimKind.GOAL, "Sibling goal", ClaimStatus.CONFIRMED,
                        siblingRoot.id(), siblingAnswer.id())), null);

        Route fork = routeService.forkFromNode(f.project().id(), f.routeId(), f.root().id(), "Fork at root");
        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.FORK);
        assertThat(ctx.routeId()).isEqualTo(fork.id());
        assertThat(ctx.includedAnswerIds()).doesNotContain(siblingAnswer.id());
    }

    @Test
    void forkFromNodeRejectsNodeFromAnotherProject() {
        Fixture f1 = createFixture();
        Fixture f2 = createFixture();
        assertThatThrownBy(() -> routeService.forkFromNode(f1.project().id(), f1.routeId(), f2.root().id(), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forkFromNodeRejectsUnknownNode() {
        Fixture f = createFixture();
        assertThatThrownBy(() -> routeService.forkFromNode(f.project().id(), f.routeId(), UUID.randomUUID(), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regenerateCreatesReplacementNodeThatSupersedesTargetNode() {
        Fixture f = createFixture();
        RegenerateResult result = commitReplacement(
                f.project().id(), f.routeId(), f.child().id(), f.child().id(), null,
                "Better child question", "Better purpose", List.of());
        Node replacement = result.replacementNode();
        assertThat(replacement.supersedesNodeId()).isEqualTo(f.child().id());
        assertThat(replacement.parentNodeId()).isEqualTo(f.child().parentNodeId());
        assertThat(replacement.question()).isEqualTo("Better child question");
    }

    @Test
    void regenerateMarksOldRouteSuperseded() {
        Fixture f = createFixture();
        RegenerateResult result = commitReplacement(
                f.project().id(), f.routeId(), f.child().id(), f.child().id(), null,
                "Better child question", "Better purpose", List.of());
        Route oldRoute = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(oldRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);
        assertThat(result.oldRoute().id()).isEqualTo(f.routeId());
    }

    @Test
    void regenerateCreatesReplacementRouteAndActivatesIt() {
        Fixture f = createFixture();
        RegenerateResult result = commitReplacement(
                f.project().id(), f.routeId(), f.child().id(), f.child().id(), null,
                "Better child question", "Better purpose", List.of());
        Route replacementRoute = result.replacementRoute();
        assertThat(replacementRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(replacementRoute.supersedesRouteId()).isEqualTo(f.routeId());
        assertThat(replacementRoute.replacementOfNodeId()).isEqualTo(f.child().id());
        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId()).isEqualTo(replacementRoute.id());
    }

    @Test
    void regenerateContextIncludesOldQuestionText() {
        Fixture f = createFixture();
        RegenerateResult result = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(result.contextSnapshot().specialInputs()).contains("Who is the first user?");
    }

    @Test
    void regenerateContextIncludesUserInstruction() {
        Fixture f = createFixture();
        RegenerateResult result = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(result.contextSnapshot().specialInputs()).contains("Make it clearer");
    }

    @Test
    void regenerateContextExcludesOldAnswer() {
        Fixture f = createFixture();
        RegenerateResult result = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(result.contextSnapshot().includedAnswerIds()).doesNotContain(f.a2().id());
    }

    @Test
    void regenerateContextExcludesOldPatch() {
        Fixture f = createFixture();
        RegenerateResult result = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(result.contextSnapshot().includedPatchIds()).doesNotContain(f.p2().id());
    }

    @Test
    void regenerateContextExcludesOldChildSubtree() {
        Fixture f = createFixture();
        RegenerateResult result = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(result.contextSnapshot().includedNodeIds()).doesNotContain(f.child().id());
    }

    @Test
    void regenerateDoesNotDeleteOldRouteNodesAnswersOrPatches() {
        Fixture f = createFixture();
        commitReplacement(
                f.project().id(), f.routeId(), f.child().id(), f.child().id(), null,
                "Better child question", "Better purpose", List.of());
        assertThat(nodeService.getNode(f.child().id())).isPresent();
        assertThat(answerService.getAnswer(f.a2().id())).isPresent();
        assertThat(answerPatchService.findByRoute(f.routeId())).extracting(AnswerPatch::id)
                .contains(f.p2().id());
    }

    @Test
    void restoredOldRouteExcludesReplacementContext() {
        Fixture f = createFixture();
        RegenerateResult result = commitReplacement(
                f.project().id(), f.routeId(), f.child().id(), f.child().id(), null,
                "Better child question", "Better purpose", List.of());
        routeService.restoreRoute(f.project().id(), f.routeId());
        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.NORMAL);
        assertThat(ctx.routeId()).isEqualTo(f.routeId());
        assertThat(ctx.includedNodeIds()).doesNotContain(result.replacementNode().id());
        assertThat(ctx.includedNodeIds()).contains(f.root().id(), f.child().id());
    }

    @Test
    void fullRouteControlFlow() {
        Fixture f = createFixture();

        Route fork = routeService.forkFromNode(f.project().id(), f.routeId(), f.root().id(), "Fork at root");
        ContextSnapshot forkCtx = contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.FORK);
        assertThat(forkCtx.routeId()).isEqualTo(fork.id());
        assertThat(forkCtx.includedNodeIds()).containsExactly(f.root().id());

        // The original route is still OPEN after creating a fork; repeating
        // restore is an illegal lifecycle transition under the hardened matrix.
        assertThatThrownBy(() -> routeService.restoreRoute(f.project().id(), f.routeId()))
                .isInstanceOf(IllegalStateException.class);

        RegenerateResult regen = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(routeService.getRoute(f.routeId()).orElseThrow().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.SUPERSEDED);
        assertThat(projectService.getProject(f.project().id()).orElseThrow().activeRouteId())
                .isEqualTo(regen.replacementRoute().id());
        assertThat(regen.contextSnapshot().includedAnswerIds()).doesNotContain(f.a2().id());
        assertThat(regen.contextSnapshot().includedPatchIds()).doesNotContain(f.p2().id());

        routeService.restoreRoute(f.project().id(), f.routeId());
        ContextSnapshot restoredCtx = contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.NORMAL);
        assertThat(restoredCtx.routeId()).isEqualTo(f.routeId());
        assertThat(restoredCtx.includedNodeIds()).doesNotContain(regen.replacementNode().id());

        routeService.softDeleteRoute(f.project().id(), regen.replacementRoute().id());
        assertThatThrownBy(() -> routeService.setActiveRoute(f.project().id(), regen.replacementRoute().id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void regenerateResultCarriesUpdatedOldRouteLifecycle() {
        Fixture f = createFixture();
        RegenerateResult result = commitReplacement(
                f.project().id(), f.routeId(), f.child().id(), f.child().id(), null,
                "Better child question", "Better purpose", List.of());

        assertThat(result.oldRoute().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.SUPERSEDED);
    }

    @Test
    void regenerateResultCarriesReplacementRouteTip() {
        Fixture f = createFixture();
        RegenerateResult result = commitReplacement(
                f.project().id(), f.routeId(), f.child().id(), f.child().id(), null,
                "Better child question", "Better purpose", List.of());

        assertThat(result.replacementRoute().tipNodeId())
                .isEqualTo(result.replacementNode().id());
    }

    @Test
    void regenerateContextHashChangesWhenUserInstructionChanges() {
        Fixture f1 = createFixture();
        RegenerateResult result1 = replacementWithContext(
                f1, "First instruction", "Better child question", "Better purpose", List.of());
        String hash1 = result1.contextSnapshot().contextHash();

        Fixture f2 = createFixture();
        RegenerateResult result2 = replacementWithContext(
                f2, "Different instruction", "Better child question", "Better purpose", List.of());
        String hash2 = result2.contextSnapshot().contextHash();

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void forkFromNodeDoesNotUseArchivedActiveRoute() {
        Fixture f = createFixture();
        routeService.archiveRoute(f.project().id(), f.routeId());

        // An archived explicit source must fail closed; Runtime must not scan
        // another route that happens to contain a related node.
        assertThatThrownBy(() -> routeService.forkFromNode(
                f.project().id(), f.routeId(), f.root().id(), "Fork"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPEN");
    }

    @Test
    void replacementDoesNotUseSupersededActiveRoute() {
        Fixture f = createFixture();

        // First regenerate to supersede the active route.
        RegenerateResult first = commitReplacement(
                f.project().id(), f.routeId(), f.child().id(), f.child().id(), null,
                "Better child question", "Better purpose", List.of());
        assertThat(first.oldRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);

        // The replacement route is now active.
        Route replacementRoute = first.replacementRoute();
        assertThat(replacementRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);

        // Regenerate again from the replacement route's tip.
        RegenerateResult second = commitReplacement(
                f.project().id(), replacementRoute.id(), replacementRoute.tipNodeId(),
                replacementRoute.tipNodeId(), null,
                "Even better question", "Even better purpose", List.of());

        // The first replacement route should now be superseded.
        assertThat(second.oldRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);
    }

    @Test
    void forkFromNodeSupportsMiddleHistoricalNode() {
        Project project = projectService.createProject("Middle node project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "What are you clarifying?",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Who is the first user?", null, List.of(), true);
        Node grandchild = nodeService.createChildNode(project.id(), routeId, child.id(),
                "What is their budget?", null, List.of(), true);
        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId())
                .isEqualTo(grandchild.id());

        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "Child answer", "user");
        Route fork = routeService.forkFromNode(project.id(), routeId, child.id(), "Fork from middle");

        assertThat(fork.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(fork.rootNodeId()).isEqualTo(root.id());
        assertThat(fork.tipNodeId()).isEqualTo(child.id());
        assertThat(fork.createdFromNodeId()).isEqualTo(child.id());
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(fork.id());

        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(
                project.id(), null, ContextOperationType.FORK);
        assertThat(ctx.includedNodeIds()).containsExactly(root.id(), child.id());
        assertThat(ctx.includedNodeIds()).doesNotContain(grandchild.id());
    }

    @Test
    void replacementSupportsMiddleHistoricalNode() {
        Project project = projectService.createProject("Regenerate middle project");
        UUID originalRouteId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), originalRouteId,
                "What are you clarifying?", null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), originalRouteId, root.id(),
                "Who is the first user?", null, List.of(), true);
        Node grandchild = nodeService.createChildNode(project.id(), originalRouteId, child.id(),
                "What is their budget?", null, List.of(), true);
        assertThat(routeService.getRoute(originalRouteId).orElseThrow().tipNodeId())
                .isEqualTo(grandchild.id());

        RegenerateResult committed = commitReplacement(
                project.id(), originalRouteId, child.id(), grandchild.id(), null,
                "Replacement child question", "Replacement child purpose", List.of());
        ContextSnapshot context = contextBuilder.buildForRegenerate(
                project.id(), originalRouteId, child.id(), committed.replacementRoute().id(),
                committed.replacementNode().id(), "Regenerate middle node");
        RegenerateResult result = new RegenerateResult(
                committed.oldRoute(), committed.replacementRoute(), committed.replacementNode(), context);

        assertThat(result.oldRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);
        assertThat(result.replacementRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(result.replacementRoute().replacementOfNodeId()).isEqualTo(child.id());
        assertThat(result.replacementNode().supersedesNodeId()).isEqualTo(child.id());
        assertThat(result.replacementNode().parentNodeId()).isEqualTo(root.id());
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(result.replacementRoute().id());

        assertThat(result.contextSnapshot().includedNodeIds()).containsExactly(root.id());
        assertThat(result.contextSnapshot().includedNodeIds())
                .doesNotContain(child.id(), grandchild.id(), result.replacementNode().id());

        // The old route keeps its original tip; regeneration never repoints it.
        Route oldRoute = routeService.getRoute(originalRouteId).orElseThrow();
        assertThat(oldRoute.tipNodeId()).isEqualTo(grandchild.id());
    }
}
