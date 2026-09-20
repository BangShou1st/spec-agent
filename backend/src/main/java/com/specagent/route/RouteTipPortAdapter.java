package com.specagent.route;

import com.specagent.node.RouteTipPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Thin implementation of the node-owned {@link RouteTipPort} on top of
 * {@link RouteRepository}.
 *
 * <p>It adds no logic of its own: the tip/root mutation is the existing
 * {@code updateTipAndRoot} statement and the read is the existing
 * {@code findById}. Its only job is to keep the port's dependency direction
 * intact, so {@code NodeService} never imports the route package.
 */
@Component
public class RouteTipPortAdapter implements RouteTipPort {

    private final RouteRepository routeRepository;

    public RouteTipPortAdapter(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    @Override
    public RouteTip findTip(UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        return new RouteTip(route.rootNodeId(), route.tipNodeId());
    }

    @Override
    public void advanceTipAndRoot(UUID routeId, UUID tipNodeId, UUID rootNodeId, Instant at) {
        routeRepository.updateTipAndRoot(routeId, tipNodeId, rootNodeId, at);
    }
}
