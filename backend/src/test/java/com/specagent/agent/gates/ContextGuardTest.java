package com.specagent.agent.gates;

import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextGuardTest {

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final ContextGuard contextGuard = new ContextGuard(projectRepository, routeRepository);

    @Test
    void contextGuardAcceptsActiveOpenNormalContext() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project(projectId, routeId)));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route(routeId, projectId, RouteLifecycleStatus.OPEN)));

        ReflectionResult result = contextGuard.validate(snapshot(projectId, routeId, ContextOperationType.NORMAL));

        assertThat(result.accepted()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void contextGuardRejectsNullSnapshot() {
        ReflectionResult result = contextGuard.validate(null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).containsExactly("Context snapshot is required");
    }

    @Test
    void contextGuardRejectsNonOpenRoute() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project(projectId, routeId)));
        when(routeRepository.findById(routeId))
                .thenReturn(Optional.of(route(routeId, projectId, RouteLifecycleStatus.ARCHIVED)));

        ReflectionResult result = contextGuard.validate(snapshot(projectId, routeId, ContextOperationType.NORMAL));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Context route must be OPEN");
    }

    @Test
    void contextGuardRejectsNormalContextForInactiveRoute() {
        UUID projectId = UUID.randomUUID();
        UUID activeRouteId = UUID.randomUUID();
        UUID otherRouteId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project(projectId, activeRouteId)));
        when(routeRepository.findById(otherRouteId))
                .thenReturn(Optional.of(route(otherRouteId, projectId, RouteLifecycleStatus.OPEN)));

        ReflectionResult result = contextGuard.validate(snapshot(projectId, otherRouteId, ContextOperationType.NORMAL));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Normal context route must match project active route");
    }

    @Test
    void contextGuardAllowsRegenerateContextWithoutActiveRouteMatch() {
        UUID projectId = UUID.randomUUID();
        UUID activeRouteId = UUID.randomUUID();
        UUID regenerateRouteId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project(projectId, activeRouteId)));
        when(routeRepository.findById(regenerateRouteId))
                .thenReturn(Optional.of(route(regenerateRouteId, projectId, RouteLifecycleStatus.OPEN)));

        ReflectionResult result = contextGuard.validate(
                snapshot(projectId, regenerateRouteId, ContextOperationType.REGENERATE));

        assertThat(result.accepted()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void normalContextRejectsProjectWithNoActiveRoute() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project(projectId, null)));
        when(routeRepository.findById(routeId))
                .thenReturn(Optional.of(route(routeId, projectId, RouteLifecycleStatus.OPEN)));

        ReflectionResult result = contextGuard.validate(snapshot(projectId, routeId, ContextOperationType.NORMAL));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Normal context requires project active route");
    }

    @Test
    void contextGuardRejectsSnapshotWithoutHash() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project(projectId, routeId)));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route(routeId, projectId, RouteLifecycleStatus.OPEN)));

        ContextSnapshot snapshot = new ContextSnapshot(
                UUID.randomUUID(), projectId, routeId, null, ContextOperationType.NORMAL,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, Instant.now());

        ReflectionResult result = contextGuard.validate(snapshot);

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Context hash is required");
    }

    @Test
    void contextGuardRejectsUnknownProjectAndRoute() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        when(projectRepository.findById(any())).thenReturn(Optional.empty());
        when(routeRepository.findById(any())).thenReturn(Optional.empty());

        ReflectionResult result = contextGuard.validate(snapshot(projectId, routeId, ContextOperationType.NORMAL));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .contains("Context project does not exist: " + projectId)
                .contains("Context route does not exist: " + routeId);
    }

    private ContextSnapshot snapshot(UUID projectId, UUID routeId, ContextOperationType operationType) {
        return new ContextSnapshot(
                UUID.randomUUID(), projectId, routeId, null, operationType,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, "hash-1", Instant.now());
    }

    private Project project(UUID projectId, UUID activeRouteId) {
        return new Project(projectId, "Demo", activeRouteId, UUID.randomUUID(), Instant.now(), Instant.now());
    }

    private Route route(UUID routeId, UUID projectId, RouteLifecycleStatus status) {
        return new Route(routeId, projectId, null, null, status, "Route", null, null, null, null,
                Instant.now(), Instant.now());
    }
}