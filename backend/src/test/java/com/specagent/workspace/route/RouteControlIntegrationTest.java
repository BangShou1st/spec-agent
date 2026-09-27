package com.specagent.workspace.route;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:RouteControlIntegrationTest.java
 *
 * 测试目标:路线控制全流程的集成测试——fork 从历史节点创建 OPEN 路线并
 * 冻结有效前缀(继承回答引用、不克隆)、重新生成(regenerate)创建替换节点
 * /替换路线并取代源路线、替换上下文的冻结隔离(排除旧回答/补丁/子树、
 * 包含用户指令)、恢复与软删除的生命周期约束,以及各操作不会误用已归档或
 * 已取代的路线。
 */
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
     * 拓扑类用例直接调用现代的确定性提交边界;上下文类用例额外执行生产环境
     * 替换周期在提交之后进行的那次运行时上下文构建。
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

    /** 提交结果 + 测试自行构建的冻结 regenerate 上下文
     * (生产的提交从不填充快照)。 */
    private record RegenOutcome(RegenerateResult result, ContextSnapshot context) {}

    private RegenOutcome replacementWithContext(Fixture fixture,
                                                String instruction,
                                                String question,
                                                String purpose,
                                                List<NodeOption> options) {
        // fixture 的路线 tip 是 child 节点;替换将其冻结,并发的续写绝不能
        // 在提交中途推进它。
        RegenerateResult committed = commitReplacement(
                fixture.project().id(), fixture.routeId(), fixture.child().id(),
                fixture.child().id(), null, question, purpose, options);
        ContextSnapshot context = contextBuilder.buildForRegenerate(
                fixture.project().id(), fixture.routeId(), fixture.child().id(),
                committed.replacementRoute().id(), committed.replacementNode().id(),
                instruction);
        return new RegenOutcome(
                new RegenerateResult(committed.oldRoute(), committed.replacementRoute(),
                        committed.replacementNode()),
                context);
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
        RegenOutcome outcome = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(outcome.context().specialInputs()).contains("Who is the first user?");
    }

    @Test
    void regenerateContextIncludesUserInstruction() {
        Fixture f = createFixture();
        RegenOutcome outcome = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(outcome.context().specialInputs()).contains("Make it clearer");
    }

    @Test
    void regenerateContextExcludesOldAnswer() {
        Fixture f = createFixture();
        RegenOutcome outcome = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(outcome.context().includedAnswerIds()).doesNotContain(f.a2().id());
    }

    @Test
    void regenerateContextExcludesOldPatch() {
        Fixture f = createFixture();
        RegenOutcome outcome = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(outcome.context().includedPatchIds()).doesNotContain(f.p2().id());
    }

    @Test
    void regenerateContextExcludesOldChildSubtree() {
        Fixture f = createFixture();
        RegenOutcome outcome = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        assertThat(outcome.context().includedNodeIds()).doesNotContain(f.child().id());
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

        // 创建 fork 后原路线仍是 OPEN;对 OPEN 路线重复执行 restore 在加固后
        // 的状态矩阵下是非法的生命周期转换。
        assertThatThrownBy(() -> routeService.restoreRoute(f.project().id(), f.routeId()))
                .isInstanceOf(IllegalStateException.class);

        RegenOutcome regenOutcome = replacementWithContext(
                f, "Make it clearer", "Better child question", "Better purpose", List.of());
        RegenerateResult regen = regenOutcome.result();
        assertThat(routeService.getRoute(f.routeId()).orElseThrow().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.SUPERSEDED);
        assertThat(projectService.getProject(f.project().id()).orElseThrow().activeRouteId())
                .isEqualTo(regen.replacementRoute().id());
        assertThat(regenOutcome.context().includedAnswerIds()).doesNotContain(f.a2().id());
        assertThat(regenOutcome.context().includedPatchIds()).doesNotContain(f.p2().id());

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
        RegenOutcome outcome1 = replacementWithContext(
                f1, "First instruction", "Better child question", "Better purpose", List.of());
        String hash1 = outcome1.context().contextHash();

        Fixture f2 = createFixture();
        RegenOutcome outcome2 = replacementWithContext(
                f2, "Different instruction", "Better child question", "Better purpose", List.of());
        String hash2 = outcome2.context().contextHash();

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void forkFromNodeDoesNotUseArchivedActiveRoute() {
        Fixture f = createFixture();
        routeService.archiveRoute(f.project().id(), f.routeId());

        // 已归档的显式源必须快速失败;运行时绝不能扫描恰好包含相关节点的
        // 其他路线。
        assertThatThrownBy(() -> routeService.forkFromNode(
                f.project().id(), f.routeId(), f.root().id(), "Fork"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPEN");
    }

    @Test
    void replacementDoesNotUseSupersededActiveRoute() {
        Fixture f = createFixture();

        // 第一次 regenerate 取代活跃路线。
        RegenerateResult first = commitReplacement(
                f.project().id(), f.routeId(), f.child().id(), f.child().id(), null,
                "Better child question", "Better purpose", List.of());
        assertThat(first.oldRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);

        // 替换路线现在是活跃路线。
        Route replacementRoute = first.replacementRoute();
        assertThat(replacementRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);

        // 从替换路线的 tip 再次 regenerate。
        RegenerateResult second = commitReplacement(
                f.project().id(), replacementRoute.id(), replacementRoute.tipNodeId(),
                replacementRoute.tipNodeId(), null,
                "Even better question", "Even better purpose", List.of());

        // 第一条替换路线现在应被取代。
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
                committed.oldRoute(), committed.replacementRoute(), committed.replacementNode());

        assertThat(result.oldRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);
        assertThat(result.replacementRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(result.replacementRoute().replacementOfNodeId()).isEqualTo(child.id());
        assertThat(result.replacementNode().supersedesNodeId()).isEqualTo(child.id());
        assertThat(result.replacementNode().parentNodeId()).isEqualTo(root.id());
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(result.replacementRoute().id());

        assertThat(context.includedNodeIds()).containsExactly(root.id());
        assertThat(context.includedNodeIds())
                .doesNotContain(child.id(), grandchild.id(), result.replacementNode().id());

        // 旧路线保留其原始 tip;regenerate 绝不重指它。
        Route oldRoute = routeService.getRoute(originalRouteId).orElseThrow();
        assertThat(oldRoute.tipNodeId()).isEqualTo(grandchild.id());
    }
}
