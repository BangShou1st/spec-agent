package com.specagent.workspace.spec;

import com.specagent.common.ApiException;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import com.specagent.workspace.spec.SpecMarkdownExporter;
import com.specagent.workspace.spec.SpecSnapshot;
import com.specagent.workspace.spec.SpecSnapshotService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:SpecController.java
 *
 * 用途:规格的读取 API。快照是派生产物,一律只读暴露;按 route 读取时校验
 * 项目归属,项目 A 的 route 绝不可能经由项目 B 读到。Markdown 导出按需渲染
 * 已存储的快照——导出器是纯视图,绝不落库第二份副本。
 */
@RestController
public class SpecController {

    private final SpecSnapshotService specSnapshotService;
    private final SpecMarkdownExporter specMarkdownExporter;
    private final ProjectService projectService;
    private final RouteService routeService;

    public SpecController(SpecSnapshotService specSnapshotService,
                          SpecMarkdownExporter specMarkdownExporter,
                          ProjectService projectService,
                          RouteService routeService) {
        this.specSnapshotService = specSnapshotService;
        this.specMarkdownExporter = specMarkdownExporter;
        this.projectService = projectService;
        this.routeService = routeService;
    }

    @GetMapping("/api/v1/specs/{snapshotId}")
    public SpecSnapshotResponse getSnapshot(@PathVariable UUID snapshotId) {
        return specSnapshotService.getSnapshot(snapshotId)
                .map(SpecSnapshotResponse::from)
                .orElseThrow(() -> ApiException.notFound("SPEC_NOT_FOUND", "Spec snapshot not found"));
    }

    /**
     * 单个快照的 Markdown 导出。`variant=snapshot` 是忠实、溯源完整的导出;
     * `variant=delivery` 是开发交付文档。渲染完全确定——不调用模型。
     */
    @GetMapping(value = "/api/v1/specs/{snapshotId}/export.md",
            produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportSnapshot(@PathVariable UUID snapshotId,
                                                 @RequestParam(name = "variant", defaultValue = "delivery")
                                                 String variant) {
        SpecMarkdownExporter.Variant parsed;
        try {
            parsed = SpecMarkdownExporter.Variant.fromCode(variant);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(
                    "SPEC_EXPORT_VARIANT_INVALID",
                    "variant must be one of: snapshot, delivery");
        }
        SpecSnapshot snapshot = specSnapshotService.getSnapshot(snapshotId)
                .orElseThrow(() -> ApiException.notFound("SPEC_NOT_FOUND", "Spec snapshot not found"));
        String projectTitle = projectService.getProject(snapshot.projectId())
                .map(project -> project.title())
                .orElse("未命名项目");
        String markdown = specMarkdownExporter.export(snapshot, projectTitle, parsed);
        String filename = "spec-" + snapshotId.toString().substring(0, 8)
                + "-" + parsed.code() + ".md";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(markdown);
    }

    @GetMapping("/api/v1/projects/{projectId}/routes/{routeId}/specs")
    public List<SpecSnapshotResponse> listRouteSpecs(@PathVariable UUID projectId,
                                                     @PathVariable UUID routeId) {
        projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        Route route = routeService.getRoute(routeId)
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "Route not found"));
        if (!route.projectId().equals(projectId)) {
            throw ApiException.notFound("ROUTE_NOT_FOUND", "Route not found");
        }
        return specSnapshotService.listByRoute(routeId).stream()
                .map(SpecSnapshotResponse::from)
                .toList();
    }
}