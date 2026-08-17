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
    private final SpecSourceReferenceGuard guard = new SpecSourceReferenceGuard(
            routeRepository, nodeRepository, answerRepository, answerPatchRepository);

    private final UUID projectId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final UUID answerId = UUID.randomUUID();
    private final UUID patchId = UUID.randomUUID();
    private final Instant now = Instant.now();

    private ContextSnapshot contextSnapshot(List<UUID> answerIds, List<UUID> patchIds) {
        return new ContextSnapshot(UUID.randomUUID(), projectId, routeId, nodeId,
                ContextOperationType.NORMAL, List.of(nodeId), answerIds, patchIds,
                List.of(), null, "hash", now);
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
}