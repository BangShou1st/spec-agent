package com.specagent.readmodel.graph;

import java.util.List;
import java.util.UUID;

/**
 * Canonical read-only project graph for the workspace.
 *
 * <p>All routes are exposed on one graph with deduplicated nodes and
 * route-specific answers kept separate. Semantic relations are carried
 * separately from continuation lineage. This is a display read: it is never
 * used to change Runtime semantics, never builds or persists a
 * {@code ContextSnapshot}, and never exposes patches, context, model/provider
 * data, credentials, or AgentRun traces.
 */
public record GraphWorkspaceView(
        UUID projectId,
        UUID activeRouteId,
        List<GraphWorkspaceRouteView> routes,
        List<GraphWorkspaceNodeView> nodes,
        List<GraphWorkspaceAnswerView> answers,
        List<GraphWorkspaceRelationView> relations) {
}
