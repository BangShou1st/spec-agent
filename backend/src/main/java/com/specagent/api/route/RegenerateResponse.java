package com.specagent.api.route;

import com.specagent.api.node.NodeResponse;

import java.util.UUID;

/**
 * Replacement result: the historical source route, the new OPEN and active
 * route, and the replacement node accepted by the Runtime.
 */
public record RegenerateResponse(
        UUID projectId,
        RouteResponse oldRoute,
        RouteResponse replacementRoute,
        NodeResponse replacementNode) {
}
