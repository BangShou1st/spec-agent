package com.specagent.workspace.route;

import com.specagent.workspace.node.NodeResponse;
import com.specagent.workspace.route.RouteResponse;

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
