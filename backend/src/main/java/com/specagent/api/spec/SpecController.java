package com.specagent.api.spec;

import com.specagent.common.ApiException;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.spec.SpecMarkdownExporter;
import com.specagent.spec.SpecSnapshot;
import com.specagent.spec.SpecSnapshotService;
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
 * Spec read API.
 *
 * <p>Snapshots are derived artifacts and are exposed read-only. Route-scoped
 * reads verify project ownership so a route from project A can never be read
 * through project B. Markdown export renders the stored snapshot on demand —
 * the exporter is a pure view and never persists a second copy.
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
     * Markdown export of one snapshot. `variant=snapshot` is the faithful,
     * provenance-complete export; `variant=delivery` is the development
     * handoff document. Rendering is deterministic — no model call.
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