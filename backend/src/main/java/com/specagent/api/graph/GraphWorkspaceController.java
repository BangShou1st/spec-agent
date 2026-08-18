package com.specagent.api.graph;

import com.specagent.readmodel.graph.GraphWorkspaceQueryService;
import com.specagent.readmodel.graph.GraphWorkspaceView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only graph workspace API.
 *
 * <p>Exposes the canonical project graph through the read-model query boundary.
 * This endpoint is read-only, provider-free, model-free, and persistence-free:
 * it only composes existing runtime reads for display. It never builds a
 * {@code ContextSnapshot} and is never used to change runtime semantics.
 *
 * <p>Architecture boundary (the API layer still never depends on context,
 * model, repository, or credential):
 *
 * <pre>
 * GraphWorkspaceController
 *         ↓
 * com.specagent.readmodel.graph.GraphWorkspaceQueryService
 *         ↓
 * ProjectService / RouteService / NodeService / AnswerService
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/graph")
public class GraphWorkspaceController {

    private final GraphWorkspaceQueryService queryService;

    public GraphWorkspaceController(GraphWorkspaceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public GraphWorkspaceView getGraph(@PathVariable UUID projectId) {
        return queryService.getForProject(projectId);
    }
}
