package com.specagent.api.project;

import com.specagent.api.common.ApiException;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Project API: create, get, and list projects, plus the active project state
 * view. Controllers never touch repositories; composition beyond a single
 * service lives in {@link ProjectRuntimeQueryService}.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectRuntimeQueryService runtimeQueryService;

    public ProjectController(ProjectService projectService,
                             ProjectRuntimeQueryService runtimeQueryService) {
        this.projectService = projectService;
        this.runtimeQueryService = runtimeQueryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(@Valid @RequestBody CreateProjectRequest request) {
        Project project = projectService.createProject(request.title());
        return ProjectResponse.from(project);
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getProject(@PathVariable UUID projectId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        return ProjectResponse.from(project);
    }

    @GetMapping
    public List<ProjectSummaryResponse> listProjects() {
        return projectService.listProjects().stream()
                .map(ProjectSummaryResponse::from)
                .toList();
    }

    @GetMapping("/{projectId}/active")
    public ActiveProjectStateResponse getActiveState(@PathVariable UUID projectId) {
        return runtimeQueryService.getActiveState(projectId);
    }
}