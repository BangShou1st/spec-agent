package com.specagent.agent.gates;

import com.specagent.agent.decision.ReflectionResult;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteInheritedAnswer;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.spec.SourceKind;
import com.specagent.workspace.spec.SourceReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件名:SpecSourceReferenceGuardTest.java
 *
 * 测试目标:验证 SpecSourceReferenceGuard 对规格来源引用的合法性校验——CONTEXT 引用
 * 必须命中当前运行上下文快照,ANSWER/PATCH 引用必须在快照冻结范围内,ROUTE 引用必须是
 * 当前路线且属于同一项目;并覆盖分支路线继承自父路线的答案/补丁应放行、兄弟路线答案应拒绝的场景。
 */
class SpecSourceReferenceGuardTest {

    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final AnswerRepository answerRepository = mock(AnswerRepository.class);
    private final AnswerPatchRepository answerPatchRepository = mock(AnswerPatchRepository.class);
    private final RouteHistoryResolver routeHistoryResolver = mock(RouteHistoryResolver.class);
    private final SpecSourceReferenceGuard guard = new SpecSourceReferenceGuard(
            routeRepository, nodeRepository, answerRepository, answerPatchRepository,
            routeHistoryResolver);

    private final UUID projectId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final UUID answerId = UUID.randomUUID();
    private final UUID patchId = UUID.randomUUID();
    private final Instant now = Instant.now();

    private ContextSnapshot contextSnapshot(List<UUID> answerIds, List<UUID> patchIds) {
        return new ContextSnapshot(UUID.randomUUID(), projectId, routeId, nodeId,
                ContextOperationType.NORMAL, List.of(nodeId), answerIds, patchIds,
                List.of(), List.of(), List.of(), null, "hash", now);
    }

    @Test
    void acceptsCurrentContextReference() {
        ContextSnapshot snapshot = contextSnapshot(List.of(answerId), List.of(patchId));

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.CONTEXT, snapshot.id())));

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void rejectsUnknownContextReference() {
        ContextSnapshot snapshot = contextSnapshot(List.of(answerId), List.of(patchId));

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.CONTEXT, UUID.randomUUID())));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.contains("must match the run context snapshot"));
    }

    @Test
    void rejectsAnswerReferenceNotInContext() {
        Answer answer = new Answer(answerId, projectId, routeId, nodeId, null, "text", "user", now);
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(answer));
        ContextSnapshot snapshot = contextSnapshot(List.of(), List.of(patchId));

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.ANSWER, answerId)));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.contains("not in the run context"));
    }

    @Test
    void rejectsPatchReferenceNotInContext() {
        AnswerPatch patch = new AnswerPatch(patchId, projectId, routeId, nodeId, answerId,
                List.of(), null, now);
        when(answerPatchRepository.findById(patchId)).thenReturn(Optional.of(patch));
        ContextSnapshot snapshot = contextSnapshot(List.of(answerId), List.of());

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.PATCH, patchId)));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.contains("not in the run context"));
    }

    @Test
    void acceptsCurrentRouteReference() {
        Route currentRoute = new Route(routeId, projectId, nodeId, nodeId,
                RouteLifecycleStatus.OPEN, "current", null, null, null, null, now, now);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(currentRoute));
        ContextSnapshot snapshot = contextSnapshot(List.of(answerId), List.of(patchId));

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.ROUTE, routeId)));

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void rejectsSameProjectSiblingRouteReference() {
        UUID siblingRouteId = UUID.randomUUID();
        Route siblingRoute = new Route(siblingRouteId, projectId, nodeId, nodeId,
                RouteLifecycleStatus.OPEN, "sibling", null, null, null, null, now, now);
        when(routeRepository.findById(siblingRouteId)).thenReturn(Optional.of(siblingRoute));
        ContextSnapshot snapshot = contextSnapshot(List.of(answerId), List.of(patchId));

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.ROUTE, siblingRouteId)));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.contains("not the current route"));
    }

    @Test
    void rejectsForeignRouteReference() {
        UUID foreignProjectId = UUID.randomUUID();
        Route foreignRoute = new Route(routeId, foreignProjectId, null, null,
                RouteLifecycleStatus.OPEN, "foreign", null, null, null, null, now, now);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(foreignRoute));
        ContextSnapshot snapshot = contextSnapshot(List.of(answerId), List.of(patchId));

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.ROUTE, routeId)));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.contains("does not belong to project"));
    }

    /**
     * 分支路线的规格可以引用它从父路线继承来的答案:ContextBuilder 会把这些答案
     * (通过 {@code route_inherited_answers})冻结进分支的快照,如果拒绝这些引用,
     * 分支路线上的所有规格都会确定性地校验失败。
     */
    @Test
    void acceptsInheritedAnswerFromTheForkedOffRoute() {
        UUID ownerRouteId = UUID.randomUUID();
        UUID inheritedAnswerId = UUID.randomUUID();
        Answer inherited = new Answer(inheritedAnswerId, projectId, ownerRouteId,
                nodeId, null, "inherited answer", "user", now);
        when(answerRepository.findById(inheritedAnswerId)).thenReturn(Optional.of(inherited));
        when(routeHistoryResolver.resolveEffectiveAnswers(routeId, List.of(nodeId)))
                .thenReturn(List.of(inherited));
        ContextSnapshot snapshot = contextSnapshot(List.of(inheritedAnswerId), List.of());

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.ANSWER, inheritedAnswerId)));

        assertThat(result.accepted()).isTrue();
    }

    /** 快照中未包含的继承答案仍然要被拒绝。 */
    @Test
    void rejectsInheritedAnswerThatIsNotInTheFrozenContext() {
        UUID ownerRouteId = UUID.randomUUID();
        UUID foreignAnswerId = UUID.randomUUID();
        Answer foreign = new Answer(foreignAnswerId, projectId, ownerRouteId,
                nodeId, null, "foreign answer", "user", now);
        when(answerRepository.findById(foreignAnswerId)).thenReturn(Optional.of(foreign));
        when(routeHistoryResolver.resolveEffectiveAnswers(routeId, List.of(nodeId)))
                .thenReturn(List.of());
        ContextSnapshot snapshot = contextSnapshot(List.of(), List.of());

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.ANSWER, foreignAnswerId)));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.contains("does not belong to route"))
                .anyMatch(e -> e.contains("not in the run context"));
    }

    @Test
    void acceptsInheritedPatchFromTheForkedOffRoute() {
        UUID ownerRouteId = UUID.randomUUID();
        UUID inheritedAnswerId = UUID.randomUUID();
        UUID inheritedPatchId = UUID.randomUUID();
        Answer inherited = new Answer(inheritedAnswerId, projectId, ownerRouteId,
                nodeId, null, "inherited answer", "user", now);
        AnswerPatch inheritedPatch = new AnswerPatch(inheritedPatchId, projectId,
                ownerRouteId, nodeId, inheritedAnswerId,
                List.of(), null, now);
        when(answerPatchRepository.findById(inheritedPatchId))
                .thenReturn(Optional.of(inheritedPatch));
        when(routeHistoryResolver.resolveEffectiveAnswers(routeId, List.of(nodeId)))
                .thenReturn(List.of(inherited));
        when(answerPatchRepository.findBySourceAnswerIds(List.of(inheritedAnswerId)))
                .thenReturn(List.of(inheritedPatch));
        ContextSnapshot snapshot = contextSnapshot(List.of(inheritedAnswerId),
                List.of(inheritedPatchId));

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.PATCH, inheritedPatchId)));

        assertThat(result.accepted()).isTrue();
    }

    /** 兄弟路线的答案(同项目但不在本路线历史上)应被拒绝。 */
    @Test
    void rejectsSiblingRouteAnswerEvenIfProjectMatches() {
        UUID siblingRouteId = UUID.randomUUID();
        when(routeHistoryResolver.resolveEffectiveAnswers(routeId, List.of(nodeId)))
                .thenReturn(List.of());
        Answer sibling = new Answer(answerId, projectId, siblingRouteId,
                nodeId, null, "sibling answer", "user", now);
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(sibling));
        ContextSnapshot snapshot = contextSnapshot(List.of(), List.of());

        ReflectionResult result = guard.validate(projectId, routeId, snapshot,
                List.of(SourceReference.of(SourceKind.ANSWER, answerId)));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.contains("does not belong to route"));
    }
}