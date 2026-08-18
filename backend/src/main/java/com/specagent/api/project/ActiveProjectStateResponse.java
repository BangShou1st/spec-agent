package com.specagent.api.project;

import com.specagent.api.node.NodeResponse;
import com.specagent.api.route.RouteResponse;

/**
 * Runtime-visible active project state.
 *
 * <p>Conceptually {@code {project, activeRoute, activeNode}}. {@code active}
 * follows {@code Project.activeRouteId} only; it is not a route lifecycle
 * status. {@code activeRoute} is {@code null} when the project has no active
 * route, and {@code activeNode} is {@code null} when the active route exists
 * but has no tip node yet. No initial node is ever invented.
 */
public record ActiveProjectStateResponse(
        ProjectResponse project,
        RouteResponse activeRoute,
        NodeResponse activeNode) {
}