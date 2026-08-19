package com.specagent.route;

import java.util.UUID;

/** One immutable Answer reference frozen into a branch route prefix. */
public record RouteInheritedAnswer(
        UUID branchRouteId,
        int ordinal,
        UUID nodeId,
        UUID answerId,
        UUID ownerRouteId) {
}
