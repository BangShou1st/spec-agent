package com.specagent.project;

import com.specagent.common.Ids;
import com.specagent.profile.ProfileService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
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
 * {@link com.specagent.route.RouteService}; this service intentionally does not
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
                RouteLifecycleStatus.OPEN, "Initial route", null, null, null, null, now, now);
        routeRepository.save(initialRoute);

        projectRepository.updateActiveRoute(projectId, routeId, now);
        return new Project(projectId, title, routeId,
                profileService.getDefaultProfileId(), now, now);
    }

    public Optional<Project> getProject(UUID projectId) {
        return projectRepository.findById(projectId);
    }

    /**
     * Lists all projects in deterministic order ({@code created_at} ascending).
     * Read-only; never mutates project or route state.
     */
    public List<Project> listProjects() {
        return projectRepository.findAll();
    }
}
