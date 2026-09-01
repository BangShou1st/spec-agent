package com.specagent.project;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectRepository {

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

    /**
     * Lists all projects in deterministic order ({@code created_at} ascending,
     * then {@code id} ascending as a stable tiebreak).
     */
    public List<Project> findAll() {
        String sql = "SELECT * FROM projects ORDER BY created_at, id";
        return jdbcTemplate.query(sql, rowMapper);
    }
}
