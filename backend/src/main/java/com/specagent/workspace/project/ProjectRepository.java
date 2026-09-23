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
 * Implements the route-side {@link ProjectActiveRoutePort} so the route domain
 * can serialize on the project row and maintain the active-route pointer
 * without depending on this package (dependency inversion; the route side of
 * project &lt;-&gt; route stays acyclic).
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

    /** Renames the project and bumps updated_at; returns rows affected. */
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
     * Locks the project row for the current transaction, or fails fast when
     * the project does not exist. Used to serialize write commands whose
     * decision depends on project-wide graph state — e.g. semantic-relation
     * creation, where the cycle validation and duplicate check must observe a
     * stable relation graph. The lock is per project row; it never locks
     * other projects.
     */
    public void lockById(UUID id) {
        String sql = "SELECT id FROM projects WHERE id = :id FOR UPDATE";
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
        // Contract: a missing PROJECT still throws (matching findById-based
        // callers); an existing project without an active route yields empty.
        Project project = findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        return Optional.ofNullable(project.activeRouteId());
    }

    /**
     * Lists all projects in deterministic order ({@code created_at} ascending,
     * then {@code id} ascending as a stable tiebreak).
     */
    public List<Project> findAll() {
        String sql = "SELECT * FROM projects ORDER BY created_at, id";
        return jdbcTemplate.query(sql, rowMapper);
    }

    /**
     * Case-insensitive exact title match. Project titles must be unique among
     * the projects that currently exist; a deleted project frees its title.
     */
    public boolean existsByTitleIgnoreCase(String title) {
        String sql = "SELECT COUNT(*) FROM projects WHERE lower(title) = lower(:title)";
        Integer count = jdbcTemplate.queryForObject(sql, Maps.of("title", title), Integer.class);
        return count != null && count > 0;
    }

    /**
     * Same as {@link #existsByTitleIgnoreCase(String)} but ignores one project.
     * Renaming a project to its own title must stay allowed, so the project
     * being renamed is excluded from the check.
     */
    public boolean existsByTitleIgnoreCase(String title, UUID excludeProjectId) {
        String sql = "SELECT COUNT(*) FROM projects WHERE lower(title) = lower(:title) AND id <> :excludeId";
        Integer count = jdbcTemplate.queryForObject(
                sql, Maps.of("title", title, "excludeId", excludeProjectId), Integer.class);
        return count != null && count > 0;
    }

    /**
     * Case-insensitive substring match on the title. The raw input is escaped so
     * that {@code %}, {@code _} and {@code \} — which are ILIKE wildcards — are
     * matched literally, then wrapped with {@code %} to match anywhere. Ordering
     * matches {@link #findAll()} so the list and a filtered list sort identically.
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
