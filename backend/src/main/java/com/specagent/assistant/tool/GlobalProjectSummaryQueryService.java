package com.specagent.assistant.tool;

import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.spec.SpecSnapshotRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 文件名:GlobalProjectSummaryQueryService.java
 *
 * 用途:面向助手的有界项目摘要查询(project.get_summary 的后端)。
 * 只产出标题、活跃路线、路线/节点计数、最近活跃时间和规格可用性等
 * 摘要字段;绝不倾倒完整图谱、需求条目、路线明细或内部快照。
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
        for (Route route : projectRoutes) {
            if (!specs.findByRoute(route.id()).isEmpty()) {
                specAvailable = true;
                break;
            }
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
