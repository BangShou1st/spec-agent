package com.specagent.globalassistant.tool;

import com.specagent.node.NodeRepository;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import com.specagent.spec.SpecSnapshotRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bounded application-level project summary for the assistant.
 * Never dumps the full graph / claims / routes / internal snapshots.
 */
@Service
public class GlobalProjectSummaryQueryService {
    private final ProjectService projects;
    private final RouteRepository routes;
    private final NodeRepository nodes;
    private final SpecSnapshotRepository specs;
    public GlobalProjectSummaryQueryService(ProjectService projects, RouteRepository routes,
            NodeRepository nodes, SpecSnapshotRepository specs) {
        this.projects = projects;
        this.routes = routes;
        this.nodes = nodes;
        this.specs = specs;
    }
    public Optional<Map<String, Object>> summarize(UUID projectId) {
        Optional<Project> project = projects.getProject(projectId);
        if (project.isEmpty()) {
            return Optional.empty();
        }
        Project p = project.get();
        List<Route> projectRoutes = routes.findByProject(projectId);
        int routeCount = projectRoutes.size();
        int nodeCount = nodes.findByProject(projectId).size();
        String activeRouteTitle = null;
        if (p.activeRouteId() != null) {
            activeRouteTitle = projectRoutes.stream()
                    .filter(r -> r.id().equals(p.activeRouteId()))
                    .map(Route::label)
                    .findFirst()
                    .orElse(null);
        }
        Instant latest = p.updatedAt();
        for (Route route : projectRoutes) {
            if (route.updatedAt() != null && route.updatedAt().isAfter(latest)) {
                latest = route.updatedAt();
            }
        }
        boolean specAvailable = false;
        try {
            for (Route route : projectRoutes) {
                if (!specs.findByRoute(route.id()).isEmpty()) {
                    specAvailable = true;
                    break;
                }
            }
        } catch (Exception ex) {
            specAvailable = false;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectId", p.id().toString());
        summary.put("title", p.title());
        summary.put("updatedAt", p.updatedAt().toString());
        if (p.activeRouteId() != null) {
            summary.put("activeRouteId", p.activeRouteId().toString());
        }
        if (activeRouteTitle != null) {
            summary.put("activeRouteTitle", activeRouteTitle);
        }
        summary.put("routeCount", routeCount);
        summary.put("nodeCount", nodeCount);
        summary.put("latestActivityAt", latest.toString());
        summary.put("specAvailable", specAvailable);
        return Optional.of(summary);
    }
}
