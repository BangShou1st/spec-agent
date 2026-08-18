package com.specagent.api.route;

import com.specagent.api.node.NodeResponse;

import java.util.UUID;

/**
 * Deterministic regenerate result: the superseded old route, the new OPEN and
 * active replacement route, and the replacement node created by the runtime.
 */
public record RegenerateResponse(
        UUID projectId,
        RouteResponse oldRoute,
        RouteResponse replacementRoute,
        NodeResponse replacementNode) {
}