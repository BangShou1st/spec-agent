package com.specagent.project;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Transactional project deletion owner.
 *
 * <p>Deletes a durable project and every project-owned row in FK-safe order.
 * FK graph (all `project_id` unless noted): routes, nodes (self-FKs parent/supersedes),
 * answers (route, node), answer_patches (route, node, answers), agent_runs (route, node),
 * agent_run_events + continuation_checks (via agent_runs), agent_proposals (logical project_id),
 * context_snapshots (route, node; cascades to agent_input_projections), spec_snapshots
 * (route, node, context), route_inherited_answers (via routes), node_relations (via nodes),
 * graph_operations, capability_invocations, skill_activations. GA conversation tables are
 * application-scoped and are never project-owned, so they are untouched.
 *
 * <p>Order is leaf-first: events/checks/proposals, inherited answers (they reference
 * answers), specs, contexts (cascade projections), patches then answers, runs,
 * relations, operations, invocations, activations, then routes (after self-FKs
 * cleared), nodes (after self-FKs cleared), finally the project row. Any failure
 * rolls back the whole transaction; no orphan rows.
 * Running agent_runs block deletion with a conflict so an active execution is never torn down.
 */
@Service
public class ProjectDeletionService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ProjectRepository projects;

    public ProjectDeletionService(NamedParameterJdbcTemplate jdbc, ProjectRepository projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    @Transactional
    public void deleteProject(UUID projectId) {
        projects.lockById(projectId);
        Integer running = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE project_id = :projectId AND status = 'RUNNING'",
                Map.of("projectId", projectId), Integer.class);
        if (running != null && running > 0) {
            throw new IllegalStateException("Project has running agent runs");
        }
        Map<String, Object> p = Maps.of("projectId", projectId);
        jdbc.update("DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = :projectId)", p);
        jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = :projectId)", p);
        jdbc.update("DELETE FROM agent_proposals WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM route_inherited_answers WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = :projectId)", p);
        jdbc.update("DELETE FROM spec_snapshots WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM context_snapshots WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM answer_patches WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM answers WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM agent_runs WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM node_relations WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM graph_operations WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM capability_invocations WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM skill_activations WHERE project_id = :projectId", p);
        jdbc.update("UPDATE routes SET supersedes_route_id = NULL, source_route_id = NULL WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM routes WHERE project_id = :projectId", p);
        jdbc.update("UPDATE nodes SET parent_node_id = NULL, supersedes_node_id = NULL WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM nodes WHERE project_id = :projectId", p);
        int deleted = jdbc.update("DELETE FROM projects WHERE id = :projectId", Maps.of("projectId", projectId));
        if (deleted != 1) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
    }
}
