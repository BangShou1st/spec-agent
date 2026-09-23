package com.specagent.workspace.project;

import com.specagent.common.Ids;
import com.specagent.workspace.profile.ProfileService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and retrieves requirement exploration projects.
 *
 * <p>Project creation also opens an initial {@code open} route and sets it as the
 * active route. Active-route control and route lifecycle transitions are owned by
 * {@link com.specagent.workspace.route.RouteService}; this service intentionally does not
 * expose an active-route setter that could bypass lifecycle validation.
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final ProfileService profileService;

    public ProjectService(ProjectRepository projectRepository,
                          RouteRepository routeRepository,
                          ProfileService profileService) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.profileService = profileService;
    }

    /**
     * Enforces the unique-title rule for user-driven create/rename.
     *
     * <p>The check is opt-in rather than baked into
     * {@link #createProject(String)}: this factory is also used by tests and
     * seeders that legitimately build fixtures with repeated titles, while the
     * product surface must never accept a duplicate. Deleting a project frees
     * its title, so recreating the same name afterwards stays allowed.
     */
    public void requireTitleAvailable(String title) {
        String normalized = title == null ? "" : title.trim();
        if (projectRepository.existsByTitleIgnoreCase(normalized)) {
            throw new DuplicateProjectTitleException(normalized);
        }
    }

    /**
     * Same as {@link #requireTitleAvailable(String)} but ignores one project,
     * so a project can keep its own title when only other fields change.
     */
    public void requireTitleAvailable(String title, UUID excludeProjectId) {
        String normalized = title == null ? "" : title.trim();
        if (projectRepository.existsByTitleIgnoreCase(normalized, excludeProjectId)) {
            throw new DuplicateProjectTitleException(normalized);
        }
    }

    public Project createProject(String title) {
        UUID projectId = Ids.random();
        UUID routeId = Ids.random();
        Instant now = Instant.now();

        // Insert the project first so the route's project_id FK is satisfiable,
        // then open the initial route and point the project's active route at it.
        Project project = new Project(projectId, title, null,
                profileService.getDefaultProfileId(), now, now);
        projectRepository.save(project);

        Route initialRoute = new Route(routeId, projectId, null, null,
                RouteLifecycleStatus.OPEN, "主路线", null, null, null, null, now, now);
        routeRepository.save(initialRoute);

        projectRepository.updateActiveRoute(projectId, routeId, now);
        return new Project(projectId, title, routeId,
                profileService.getDefaultProfileId(), now, now);
    }

    public Optional<Project> getProject(UUID projectId) {
        return projectRepository.findById(projectId);
    }

    /**
     * Renames a project. Title validation matches creation rules (non-blank,
     * bounded); unknown ids fail with the same not-found semantics as reads.
     */
    public Project renameProject(UUID projectId, String title) {
        String normalized = requireValidTitle(title);
        int updated = projectRepository.updateTitle(projectId, normalized, Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    private static String requireValidTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Project title must not be blank");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("Project title must not exceed 255 characters");
        }
        return trimmed;
    }

    /**
     * Lists all projects in deterministic order ({@code created_at} ascending).
     * Read-only; never mutates project or route state.
     */
    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    /**
     * Lists projects whose title contains {@code title} (case-insensitive
     * substring). A blank or null query returns every project, identical to
     * {@link #listProjects()}, so the list endpoint stays backward compatible
     * when the parameter is omitted.
     */
    public List<Project> listProjects(String title) {
        if (title == null || title.isBlank()) {
            return projectRepository.findAll();
        }
        return projectRepository.findByTitleContaining(title);
    }
}
