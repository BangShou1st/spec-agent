package com.specagent.route;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages explicit exploration routes and their lifecycle status.
 *
 * <p>Lifecycle status is {@code open | superseded | archived | deleted}. The
 * active route is tracked by {@code Project.activeRouteId}, never by a route
 * status. This service is deterministic and does not call any model.
 */
@Service
public class RouteService {

    private final RouteRepository routeRepository;

    public RouteService(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    public Route createRoute(UUID projectId, RouteLifecycleStatus status, String label) {
        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route route = new Route(routeId, projectId, null, null, status, label,
                null, null, null, null, now, now);
        routeRepository.save(route);
        return route;
    }

    public void updateTip(UUID routeId, UUID tipNodeId, UUID rootNodeId) {
        routeRepository.updateTipAndRoot(routeId, tipNodeId, rootNodeId, Instant.now());
    }

    public void changeLifecycle(UUID routeId, RouteLifecycleStatus status) {
        routeRepository.updateLifecycle(routeId, status, Instant.now());
    }

    public Optional<Route> getRoute(UUID routeId) {
        return routeRepository.findById(routeId);
    }

    public List<Route> listRoutes(UUID projectId) {
        return routeRepository.findByProject(projectId);
    }
}
