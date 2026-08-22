package com.specagent.agent.contract;

import java.util.UUID;

/** The route/read context the snapshot was built from. */
public record RouteContextView(UUID routeId, UUID tipNodeId, String label) {
}
