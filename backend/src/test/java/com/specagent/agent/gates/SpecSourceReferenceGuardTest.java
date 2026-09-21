package com.specagent.agent.gates;

import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.NodeRepository;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteInheritedAnswer;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import com.specagent.spec.SourceKind;
import com.specagent.spec.SourceReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
     * A branch route's spec may cite the answers it inherited from the route it
     * forked off: the ContextBuilder freezes those answers into the branch's
     * snapshot (via {@code route_inherited_answers}), so rejecting them made
     * every spec on a forked route fail deterministically.
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

    /** An inherited answer the snapshot did NOT include is still rejected. */
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

    /** A sibling route's answer (same project, not on this route's history) is rejected. */
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