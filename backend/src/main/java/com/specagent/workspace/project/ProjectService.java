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
 * 文件名:ProjectService.java
 *
 * 用途:创建和检索需求探索项目。项目创建会同时打开一条初始的
 * {@code open} 路线并将其设为活跃路线。活跃路线控制与路线生命周期迁移
 * 由 {@link com.specagent.workspace.route.RouteService} 负责;本服务刻意
 * 不暴露任何可能绕过生命周期校验的活跃路线 setter。
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
     * 为用户驱动的创建/重命名执行标题唯一性规则。
     *
     * 该检查是按需调用而非内置在 {@link #createProject(String)} 中:
     * 这个工厂方法也被测试和数据种子器使用,它们合法地构造重复标题的
     * 夹具;而产品入口绝不能接受重复标题。删除项目会释放标题,因此
     * 之后再创建同名项目仍然被允许。
     */
    public void requireTitleAvailable(String title) {
        String normalized = title == null ? "" : title.trim();
        if (projectRepository.existsByTitleIgnoreCase(normalized)) {
            throw new DuplicateProjectTitleException(normalized);
        }
    }

    /**
     * 与 {@link #requireTitleAvailable(String)} 相同,但忽略一个指定的
     * 项目,使项目在只修改其他字段时能保留自己的标题。
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

        // 先插入项目,使路线的 project_id 外键可满足;然后打开初始路线,
        // 并把项目的活跃路线指向它。
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
     * 重命名项目。标题校验与创建时规则一致(非空、有界);未知 id 按
     * 与读取相同的 not-found 语义失败。
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
     * 按确定顺序列出全部项目({@code created_at} 升序)。
     * 只读;绝不修改项目或路线状态。
     */
    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    /**
     * 列出标题包含 {@code title} 的项目(不区分大小写的子串匹配)。
     * 查询为空白或 null 时返回全部项目,与 {@link #listProjects()} 完全
     * 一致,保证省略参数时列表端点向后兼容。
     */
    public List<Project> listProjects(String title) {
        if (title == null || title.isBlank()) {
            return projectRepository.findAll();
        }
        return projectRepository.findByTitleContaining(title);
    }
}
