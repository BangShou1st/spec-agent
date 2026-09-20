package com.specagent.api.project;

import com.specagent.application.project.ActiveProjectStateResponse;
import com.specagent.application.project.ProjectResponse;
import com.specagent.application.project.ProjectRuntimeQueryService;
import com.specagent.common.ApiException;
import com.specagent.project.DuplicateProjectTitleException;
import com.specagent.project.Project;
import com.specagent.project.ProjectDeletionService;
import com.specagent.project.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ProjectDeletionService deletionService;
    private final ProjectRuntimeQueryService runtimeQueryService;

    public ProjectController(ProjectService projectService,
                             ProjectDeletionService deletionService,
                             ProjectRuntimeQueryService runtimeQueryService) {
        this.projectService = projectService;
        this.deletionService = deletionService;
        this.runtimeQueryService = runtimeQueryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(@Valid @RequestBody CreateProjectRequest request) {
        try {
            projectService.requireTitleAvailable(request.title());
            Project project = projectService.createProject(request.title());
            return ProjectResponse.from(project);
        } catch (DuplicateProjectTitleException ex) {
            throw ApiException.conflict("PROJECT_TITLE_ALREADY_EXISTS", "Project title already exists");
        }
    }

    /** Renames a project; route state, answers and history stay untouched. */
    @PutMapping("/{projectId}/title")
    public ProjectResponse renameProject(@PathVariable UUID projectId,
                                         @Valid @RequestBody RenameProjectRequest request) {
        try {
            projectService.requireTitleAvailable(request.title(), projectId);
            return ProjectResponse.from(projectService.renameProject(projectId, request.title()));
        } catch (DuplicateProjectTitleException ex) {
            throw ApiException.conflict("PROJECT_TITLE_ALREADY_EXISTS", "Project title already exists");
        } catch (IllegalArgumentException ex) {
            throw ApiException.notFound("PROJECT_NOT_FOUND", "Project not found");
        }
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getProject(@PathVariable UUID projectId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        return ProjectResponse.from(project);
    }

    @GetMapping
    public List<ProjectSummaryResponse> listProjects(
            @RequestParam(name = "title", required = false) String title) {
        return projectService.listProjects(title).stream()
                .map(ProjectSummaryResponse::from)
                .toList();
    }

    @GetMapping("/{projectId}/active")
    public ActiveProjectStateResponse getActiveState(@PathVariable UUID projectId) {
        return runtimeQueryService.getActiveState(projectId);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable UUID projectId) {
        try {
            deletionService.deleteProject(projectId);
        } catch (IllegalArgumentException ex) {
            throw ApiException.notFound("PROJECT_NOT_FOUND", "Project not found");
        } catch (IllegalStateException ex) {
            throw ApiException.conflict("PROJECT_HAS_RUNNING_RUNS", "Project has running runs");
        }
    }
}
