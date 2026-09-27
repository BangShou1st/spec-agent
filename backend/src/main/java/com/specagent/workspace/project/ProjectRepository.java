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
        String sql = """
                INSERT INTO projects (id, title, active_route_id, default_profile_id, created_at, updated_at)
                VALUES (:id, :title, :activeRouteId, :defaultProfileId, :createdAt, :updatedAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", project.id(),
                "title", project.title(),
                "activeRouteId", project.activeRouteId(),
                "defaultProfileId", project.defaultProfileId(),
                "createdAt", Timestamp.from(project.createdAt()),
                "updatedAt", Timestamp.from(project.updatedAt())));
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
