package com.specagent.workspace.project;

import com.specagent.access.AccessDeniedException;
import com.specagent.access.ActorContext;
import com.specagent.access.AuthorizationGuard;
import com.specagent.access.Capability;
import com.specagent.common.ApiException;
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
 *
 * <b>第二阶段(身份/权限)改动</b>:本服务是项目资源授权的落点。之所以放在
 * 服务层而不是控制器层,是因为设计明确要求"避免只加几个 {@code @PreAuthorize}
 * 就漏掉 Worker/RPC"——后台任务、内部 RPC 与工具调用都必须经过同一处判定。
 *
 * 归属规则(唯一事实源 {@link com.specagent.access.OwnershipAttribution}):
 * <ul>
 *   <li>列表与标题唯一性按<b>主体组织</b>过滤。跨组织枚举项目标题是
 *       P2-A 清单的 G1,这里闭合。</li>
 *   <li>创建时落 {@code org_id}/{@code created_by},并让创建者成为该项目
 *       MANAGER——组织管理员不凭管理角色读取项目正文,因此新建项目必须由
 *       创建行为本身产生一个可治理者。</li>
 *   <li>LOCAL 模式:显式本地主体即本地组织的所有者,单机行为不变。</li>
 * </ul>
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final ProfileService profileService;
    private final AuthorizationGuard guard;

    public ProjectService(ProjectRepository projectRepository,
                          RouteRepository routeRepository,
                          ProfileService profileService,
                          AuthorizationGuard guard) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.profileService = profileService;
        this.guard = guard;
    }

    /**
     * 为用户驱动的创建/重命名执行标题唯一性规则。
     *
     * 唯一性在<b>组织范围内</b>判定:不同组织可以拥有同名项目,而同一组织
     * 内不允许重复标题。该检查是按需调用而非内置在
     * {@link #createProject(String)} 中:这个工厂方法也被测试和数据种子器
     * 使用,它们合法地构造重复标题的夹具;而产品入口绝不能接受重复标题。
     */
    public void requireTitleAvailable(String title) {
        String normalized = title == null ? "" : title.trim();
        guard.requireSelectedOrg(guard.ownershipActor(),null);
        var scope=guard.visibilityScope();
        if (projectRepository.existsVisibleTitle(scope.orgId(),scope.userId(),scope.unrestrictedInOrg(),normalized,null)) {
            throw new DuplicateProjectTitleException(normalized);
        }
    }

    /**
     * 与 {@link #requireTitleAvailable(String)} 相同,但忽略一个指定的
     * 项目,使项目在只修改其他字段时能保留自己的标题。
     */
    public void requireTitleAvailable(String title, UUID excludeProjectId) {
        requireVisible(excludeProjectId,Capability.PROJECT_MANAGE);
        String normalized = title == null ? "" : title.trim();
        var scope=guard.visibilityScope();
        if (projectRepository.existsVisibleTitle(scope.orgId(),scope.userId(),scope.unrestrictedInOrg(),normalized,excludeProjectId)) {
            throw new DuplicateProjectTitleException(normalized);
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public Project createProject(String title) {
        ActorContext actor = guard.ownershipActor();
        UUID orgId = guard.requireSelectedOrg(actor,null);
        UUID createdBy = actor.userId();
        guard.lockSharedFailClosed(java.util.List.of(com.specagent.access.AuthorizationScopes.org(orgId),
                com.specagent.access.AuthorizationScopes.user(createdBy)));
        guard.requireSelectedOrg(actor,null);

        UUID projectId = Ids.random();
        UUID routeId = Ids.random();
        Instant now = Instant.now();

        // 默认画像必须来自项目所属组织:冻结决定 #2 用复合外键
        // projects(default_profile_id, org_id) -> profiles(id, org_id)
        // 在数据库侧强制"项目只能引用同组织画像",因此这里不能直接用全局
        // 模板 id——那会让任何非本地组织的新项目插入被外键拒绝。
        UUID defaultProfileId = profileService.getDefaultProfileIdForOrg(orgId);

        // 先插入项目,使路线的 project_id 外键可满足;然后打开初始路线,
        // 并把项目的活跃路线指向它。归属列与插入同批写入:读路径按 org_id
        // 过滤,漏写归属会让新项目对任何人不可见。
        Project project = new Project(projectId, title, null, defaultProfileId, now, now);
        projectRepository.save(project, orgId, createdBy);
        projectRepository.grantProjectManager(projectId, createdBy, orgId);

        Route initialRoute = new Route(routeId, projectId, null, null,
                RouteLifecycleStatus.OPEN, "主路线", null, null, null, null, now, now);
        routeRepository.save(initialRoute);

        projectRepository.updateActiveRoute(projectId, routeId, now);
        return new Project(projectId, title, routeId, defaultProfileId, now, now);
    }

    /**
     * 不带授权的原始读取。供<b>已自行完成授权</b>的内部调用方使用
     * (例如已被 {@link #getProjectForActor} 校验过的组合查询)。
     * 产品入口与后台任务的读取一律走 {@link #getProjectForActor}。
     */
    public Optional<Project> getProject(UUID projectId) {
        return projectRepository.findById(projectId);
    }

    /**
     * 主体视角的项目读取:先判定可见性,不可见时抛 404 语义的拒绝
     * (不泄露"存在但无权")。
     */
    public Optional<Project> getProjectForActor(UUID projectId) {
        requireVisible(projectId, Capability.PROJECT_READ);
        return projectRepository.findById(projectId);
    }
    /** Called in the mutation transaction, before project/business row locks. */
    public void authorizeWrite(UUID projectId) {
        ActorContext actor=guard.ownershipActor();
        guard.lockSharedFailClosed(List.of(com.specagent.access.AuthorizationScopes.org(actor.orgId()),
                com.specagent.access.AuthorizationScopes.user(actor.userId()),
                com.specagent.access.AuthorizationScopes.project(projectId)));
        requireVisible(projectId,Capability.PROJECT_WRITE);
    }

    /**
     * 判定并抛出与<b>既有 HTTP 契约</b>一致的 {@code PROJECT_NOT_FOUND}。
     *
     * 判定本身在 {@link AuthorizationGuard};这里只做"不可见 = 不存在"的
     * 等价映射。映射的要点是两个方向都必须成立:
     * <ul>
     *   <li>不可见不能变成 403 —— 那会泄露"这个项目存在"。</li>
     *   <li>跨组织与不存在必须得到<b>完全相同</b>的响应(状态码与错误码),
     *       否则仍然可以用"码不同"来探测他人项目是否存在。</li>
     * </ul>
     * 因此两者统一落到原有的 {@code PROJECT_NOT_FOUND},既有客户端契约不变。
     */
    private void requireVisible(UUID projectId, Capability capability) {
        try {
            guard.requireProject(projectId, capability);
        } catch (AccessDeniedException denied) {
            if (denied.concealed()) {
                throw ApiException.notFound("PROJECT_NOT_FOUND", "Project not found");
            }
            throw denied;
        }
    }

    /**
     * 重命名项目。需要 {@link Capability#PROJECT_MANAGE}——已冻结决定:
     * 项目重命名属于 PROJECT_MANAGE,EDITOR 不允许。
     */
    @org.springframework.transaction.annotation.Transactional
    public Project renameProject(UUID projectId, String title) {
        authorizeManagementWrite(projectId);
        String normalized = requireValidTitle(title);
        int updated = projectRepository.updateTitle(projectId, normalized, Instant.now());
        if (updated == 0) {
            throw ApiException.notFound("PROJECT_NOT_FOUND", "Project not found");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
    }

    /** 删除前的能力判定(删除本身由 {@link ProjectDeletionService} 承担)。 */
    public void authorizeDelete(UUID projectId) {
        requireVisible(projectId, Capability.PROJECT_MANAGE);
    }
    /** Excludes index/rebuild commits before taking project/source rows during cascading deletion. */
    public void authorizeDeletionWrite(UUID projectId) {
        requireVisible(projectId,Capability.PROJECT_MANAGE);
        var actor=guard.ownershipActor();
        guard.lockProjectDeletionScopes(actor,projectId);
        requireVisible(projectId,Capability.PROJECT_MANAGE);
    }

    /** Called in the mutation transaction before acquiring a project/business row lock. */
    public void authorizeManagementWrite(UUID projectId) {
        requireVisible(projectId,Capability.PROJECT_MANAGE);
        var actor=guard.ownershipActor();
        guard.lockSharedFailClosed(List.of(com.specagent.access.AuthorizationScopes.org(actor.orgId()),
                com.specagent.access.AuthorizationScopes.user(actor.userId()),
                com.specagent.access.AuthorizationScopes.project(projectId)));
        requireVisible(projectId,Capability.PROJECT_MANAGE);
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
     * 按确定顺序列出主体<b>可见</b>的项目({@code created_at} 升序)。
     * 只读;绝不修改项目或路线状态。
     *
     * 可见性 = 组织内 + 具备项目角色(单机 LOCAL 主体在自身组织内不受此限)。
     */
    public List<Project> listProjects() {
        AuthorizationGuard.VisibilityScope scope = guard.visibilityScope();
        return projectRepository.findVisibleInOrg(
                scope.orgId(), scope.userId(), scope.unrestrictedInOrg());
    }

    /**
     * 列出主体可见、且标题包含 {@code title} 的项目(不区分大小写的子串匹配)。
     * 查询为空白或 null 时与 {@link #listProjects()} 完全一致,保证省略参数
     * 时列表端点向后兼容。
     */
    public List<Project> listProjects(String title) {
        AuthorizationGuard.VisibilityScope scope = guard.visibilityScope();
        if (title == null || title.isBlank()) {
            return projectRepository.findVisibleInOrg(
                    scope.orgId(), scope.userId(), scope.unrestrictedInOrg());
        }
        return projectRepository.findVisibleByTitleContaining(
                scope.orgId(), scope.userId(), scope.unrestrictedInOrg(), title);
    }
}
