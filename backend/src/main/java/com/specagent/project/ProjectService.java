package com.specagent.project;

import com.specagent.common.Ids;
import com.specagent.profile.ProfileService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and retrieves requirement exploration projects and manages the active
 * route pointer.
 *
 * <p>Project creation also opens an initial {@code open} route and sets it as the
 * active route. The active route is identified only by {@code activeRouteId};
 * there is no {@code active} route lifecycle status.
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

    public void setActiveRoute(UUID projectId, UUID routeId) {
        projectRepository.updateActiveRoute(projectId, routeId, Instant.now());
    }
}
