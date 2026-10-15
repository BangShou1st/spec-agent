package com.specagent.workspace.project;

import com.specagent.common.Maps;
import com.specagent.workspace.answer.ProjectRowLockPort;
import com.specagent.workspace.route.ProjectActiveRoutePort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:ProjectRepository.java
 *
 * 用途:项目聚合的 JDBC 仓储,同时实现路线侧的
 * {@link ProjectActiveRoutePort},让路线域能按项目行串行化并维护
 * 活跃路线指针,而不必依赖本包(依赖反转;project &lt;-&gt; route 的
 * route 一侧保持无循环)。
 */
@Repository
public class ProjectRepository implements ProjectActiveRoutePort, ProjectRowLockPort {
    private com.specagent.access.IdentityModeProperties identityMode;
    @org.springframework.beans.factory.annotation.Autowired
    public void setIdentityMode(com.specagent.access.IdentityModeProperties identityMode) {
        this.identityMode=identityMode;
    }

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<Project> rowMapper;

    public ProjectRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = (rs, rowNum) -> new Project(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getObject("active_route_id", UUID.class),
                rs.getObject("default_profile_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public void save(Project project) {
        if (identityMode==null) throw new IllegalStateException("Project attribution configuration is required");
        var actor=com.specagent.access.OwnershipAttribution.currentOrDefault(identityMode.accessMode());
        save(project,actor.orgId(),actor.userId());
    }

    public void updateActiveRoute(UUID projectId, UUID activeRouteId, Instant updatedAt) {
        String sql = """
                UPDATE projects SET active_route_id = :activeRouteId, updated_at = :updatedAt
                WHERE id = :projectId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "projectId", projectId,
                "activeRouteId", activeRouteId,
                "updatedAt", Timestamp.from(updatedAt)));
    }

    /** 重命名项目并顺带刷新 updated_at;返回受影响行数。 */
    public int updateTitle(UUID projectId, String title, Instant updatedAt) {
        String sql = """
                UPDATE projects SET title = :title, updated_at = :updatedAt
                WHERE id = :projectId
                """;
        return jdbcTemplate.update(sql, Maps.of(
                "projectId", projectId,
                "title", title,
                "updatedAt", Timestamp.from(updatedAt)));
    }

    /**
     * 带组织归属的项目插入(第二阶段 §4.2)。
     *
     * {@code org_id} / {@code created_by} 是授权归属的事实来源:读路径按
     * {@code org_id} 过滤,因此创建时<b>必须</b>落上归属,否则新项目对
     * 任何人都不可见(失败关闭方向,但会造成创建即消失)。
     */
    public void save(Project project, UUID orgId, UUID createdBy) {
        String sql = """
                INSERT INTO projects (id, title, active_route_id, default_profile_id,
                                      org_id, created_by, created_at, updated_at)
                VALUES (:id, :title, :activeRouteId, :defaultProfileId,
                        :orgId, :createdBy, :createdAt, :updatedAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", project.id(),
                "title", project.title(),
                "activeRouteId", project.activeRouteId(),
                "defaultProfileId", project.defaultProfileId(),
                "orgId", orgId,
                "createdBy", createdBy,
                "createdAt", Timestamp.from(project.createdAt()),
                "updatedAt", Timestamp.from(project.updatedAt())));
    }

    /**
     * 项目创建者自动成为该项目 MANAGER。
     *
     * 这是"新项目至少有一个可治理者"的最小事实:设计不允许组织管理员凭
     * 管理角色暗中读取项目正文,因此必须由创建行为本身产生项目角色,否则
     * 新建项目在 OIDC 模式下会没有任何人能读取。
     */
    public void grantProjectManager(UUID projectId, UUID userId, UUID orgId) {
        jdbcTemplate.update("""
                INSERT INTO project_members (project_id, user_id, org_id, role)
                VALUES (:projectId, :userId, :orgId, 'MANAGER')
                ON CONFLICT (project_id, user_id) DO UPDATE SET role = 'MANAGER'
                """, Maps.of("projectId", projectId, "userId", userId, "orgId", orgId));
    }

    /** 项目的所属组织;不存在或归属未定时为 empty。 */
    public Optional<UUID> findOrgId(UUID projectId) {
        String sql = "SELECT org_id FROM projects WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", projectId),
                (rs, rowNum) -> rs.getObject("org_id", UUID.class)).stream().findFirst();
    }

    /**
     * 列出组织内的全部项目。这是唯一的"列表"入口形式:项目列表必须按
     * 组织过滤,否则任一用户可枚举其他组织的项目标题(P2-A 清单 G1)。
     */
    public List<Project> findAllInOrg(UUID orgId) {
        String sql = "SELECT * FROM projects WHERE org_id = :orgId ORDER BY created_at, id";
        return jdbcTemplate.query(sql, Maps.of("orgId", orgId), rowMapper);
    }

    public List<Project> findByTitleContainingInOrg(UUID orgId, String rawTitle) {
        String escaped = rawTitle
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        String sql = "SELECT * FROM projects WHERE org_id = :orgId AND title ILIKE :pattern "
                + "ESCAPE '\\' ORDER BY created_at, id";
        return jdbcTemplate.query(sql, Maps.of("orgId", orgId, "pattern", pattern), rowMapper);
    }

    /** 标题唯一性必须在组织范围内判定:不同组织可以同名(设计 §4.2)。 */
    public boolean existsVisibleTitle(UUID orgId,UUID userId,boolean unrestricted,String title,UUID exclude) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM projects p WHERE p.org_id=:org
                    AND LOWER(p.title)=LOWER(:title) AND (CAST(:exclude AS uuid) IS NULL OR p.id<>:exclude)
                    AND (:unrestricted OR EXISTS(SELECT 1 FROM project_members m
                        WHERE m.project_id=p.id AND m.user_id=:user)))
                """,Maps.of("org",orgId,"user",userId,"unrestricted",unrestricted,"title",title,"exclude",exclude),Boolean.class));
    }

    public boolean existsByTitleIgnoreCaseInOrg(UUID orgId, String title) {
        String sql = "SELECT COUNT(*) FROM projects WHERE org_id = :orgId AND lower(title) = lower(:title)";
        Integer count = jdbcTemplate.queryForObject(sql, Maps.of("orgId", orgId, "title", title), Integer.class);
        return count != null && count > 0;
    }

    public boolean existsByTitleIgnoreCaseInOrg(UUID orgId, String title, UUID excludeProjectId) {
        String sql = "SELECT COUNT(*) FROM projects WHERE org_id = :orgId "
                + "AND lower(title) = lower(:title) AND id <> :excludeId";
        Integer count = jdbcTemplate.queryForObject(sql,
                Maps.of("orgId", orgId, "title", title, "excludeId", excludeProjectId), Integer.class);
        return count != null && count > 0;
    }

    /**
     * 主体在组织内<b>可见</b>的项目(列表与搜索的候选来源)。
     *
     * 必须同时按两个维度过滤,缺一不可:
     * <ul>
     *   <li>组织 —— 跨组织不可见;</li>
     *   <li>项目访问权限 —— 同组织但<b>没有项目角色</b>的用户也不能看到
     *       标题、数量与预览。"同组织即可见"会让组织成为一条隐式的项目
     *       读取授权。</li>
     * </ul>
     *
     * {@code unrestrictedInOrg} 只对受限本地身份适配器产生的 LOCAL 主体
     * 为真(单机交付语义),不由组织 id 推导,因此 OIDC 用户拿不到。
     *
     * 过滤发生在 SQL 内而不是取回后筛:数量与排序本身就是信息。
     */
    public List<Project> findVisibleInOrg(UUID orgId, UUID userId, boolean unrestrictedInOrg) {
        if (unrestrictedInOrg) {
            return findAllInOrg(orgId);
        }
        String sql = """
                SELECT p.* FROM projects p
                WHERE p.org_id = :orgId
                  AND EXISTS (SELECT 1 FROM project_members m
                              WHERE m.project_id = p.id AND m.user_id = :userId)
                ORDER BY p.created_at, p.id
                """;
        return jdbcTemplate.query(sql, Maps.of("orgId", orgId, "userId", userId), rowMapper);
    }

    /**
     * 主体在组织内<b>可见</b>、按最近更新排序的有界项目(GA 隐式内容检索的
     * 候选来源)。
     *
     * 与 {@link #findVisibleInOrg} 同一套可见性谓词(组织 + 项目角色),但排序
     * 为 {@code updated_at DESC, id} 且有界({@code LIMIT})——"最近更新的四个
     * 候选"必须由 SQL 选出,不能在应用层先取回候选再过滤:数量与排序本身就
     * 是信息,先取全库再隐藏会把其他组织/无角色项目的名次泄露出去。
     */
    public List<Project> findVisibleRecentInOrg(UUID orgId, UUID userId,
                                                boolean unrestrictedInOrg, int limit) {
        if (orgId == null || limit < 1) {
            return List.of();
        }
        if (unrestrictedInOrg) {
            return jdbcTemplate.query(
                    "SELECT * FROM projects WHERE org_id = :orgId ORDER BY updated_at DESC, id LIMIT :limit",
                    Maps.of("orgId", orgId, "limit", limit), rowMapper);
        }
        String sql = """
                SELECT p.* FROM projects p
                WHERE p.org_id = :orgId
                  AND EXISTS (SELECT 1 FROM project_members m
                              WHERE m.project_id = p.id AND m.user_id = :userId)
                ORDER BY p.updated_at DESC, p.id LIMIT :limit
                """;
        return jdbcTemplate.query(sql, Maps.of("orgId", orgId, "userId", userId, "limit", limit), rowMapper);
    }

    public List<Project> findVisibleByTitleContaining(UUID orgId, UUID userId,
                                                      boolean unrestrictedInOrg, String rawTitle) {
        if (unrestrictedInOrg) {
            return findByTitleContainingInOrg(orgId, rawTitle);
        }
        String escaped = rawTitle
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        String sql = """
                SELECT p.* FROM projects p
                WHERE p.org_id = :orgId
                  AND EXISTS (SELECT 1 FROM project_members m
                              WHERE m.project_id = p.id AND m.user_id = :userId)
                  AND p.title ILIKE :pattern ESCAPE '\\'
                ORDER BY p.created_at, p.id
                """;
        return jdbcTemplate.query(sql,
                Maps.of("orgId", orgId, "userId", userId, "pattern", pattern), rowMapper);
    }

    public Optional<Project> findById(UUID id) {
        String sql = "SELECT * FROM projects WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    /**
     * 为当前事务锁定项目行,项目不存在时快速失败。用于串行化那些"决策
     * 依赖项目级图状态"的写命令——例如语义关系的创建:环校验与重复
     * 检查必须观察到一个稳定的关系图。锁只作用于单个项目行,
     * 绝不锁定其他项目。
     */
    public void lockById(UUID id) {
        String sql = "SELECT id FROM projects WHERE id = :id FOR UPDATE";
        List<UUID> locked = jdbcTemplate.queryForList(sql, Maps.of("id", id), UUID.class);
        if (locked.isEmpty()) {
            throw new IllegalArgumentException("Project not found: " + id);
        }
    }

    /**
     * 入队专用的弱化项目行锁(FOR KEY SHARE)。与删除保护的 FOR UPDATE
     * 互斥——入队与删除仍然串行化——但不与其他入队(包括同线程嵌套的
     * REQUIRES_NEW 验收事务再次入队)互斥,避免"外层事务持锁、内层事务
     * 再取同一把写锁"的自死锁。项目不存在时快速失败。
     */
    public void lockByIdForKeyShare(UUID id) {
        String sql = "SELECT id FROM projects WHERE id = :id FOR KEY SHARE";
        List<UUID> locked = jdbcTemplate.queryForList(sql, Maps.of("id", id), UUID.class);
        if (locked.isEmpty()) {
            throw new IllegalArgumentException("Project not found: " + id);
        }
    }

    @Override
    public void lockProject(UUID projectId) {
        lockById(projectId);
    }

    @Override
    public Optional<UUID> findActiveRouteId(UUID projectId) {
        // 契约:项目不存在时仍然抛异常(与基于 findById 的调用方一致);
        // 项目存在但没有活跃路线时返回 empty。
        Project project = findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        return Optional.ofNullable(project.activeRouteId());
    }

    /**
     * 按确定顺序列出全部项目({@code created_at} 升序,再以 {@code id}
     * 升序作为稳定的次序补充)。
     */
    public List<Project> findAll() {
        String sql = "SELECT * FROM projects ORDER BY created_at, id";
        return jdbcTemplate.query(sql, rowMapper);
    }

    /**
     * 不区分大小写的标题精确匹配。项目标题必须在"当前存在的项目"中
     * 唯一;被删除的项目会释放其标题。
     */
    public boolean existsByTitleIgnoreCase(String title) {
        String sql = "SELECT COUNT(*) FROM projects WHERE lower(title) = lower(:title)";
        Integer count = jdbcTemplate.queryForObject(sql, Maps.of("title", title), Integer.class);
        return count != null && count > 0;
    }

    /**
     * 与 {@link #existsByTitleIgnoreCase(String)} 相同,但忽略一个指定的
     * 项目。把项目重命名为它自己的标题必须被允许,因此检查时要排除
     * 正在被重命名的项目。
     */
    public boolean existsByTitleIgnoreCase(String title, UUID excludeProjectId) {
        String sql = "SELECT COUNT(*) FROM projects WHERE lower(title) = lower(:title) AND id <> :excludeId";
        Integer count = jdbcTemplate.queryForObject(
                sql, Maps.of("title", title, "excludeId", excludeProjectId), Integer.class);
        return count != null && count > 0;
    }

    /**
     * 不区分大小写的标题子串匹配。原始输入会被转义,使 {@code %}、
     * {@code _} 和 {@code \}(它们是 ILIKE 通配符)按字面匹配,再包上
     * {@code %} 实现任意位置匹配。排序与 {@link #findAll()} 一致,
     * 保证全量列表与过滤列表的排序相同。
     */
    public List<Project> findByTitleContaining(String rawTitle) {
        String escaped = rawTitle
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        String sql = "SELECT * FROM projects WHERE title ILIKE :pattern ESCAPE '\\' ORDER BY created_at, id";
        return jdbcTemplate.query(sql, Maps.of("pattern", pattern), rowMapper);
    }
}
