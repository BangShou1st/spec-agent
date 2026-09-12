package com.specagent.api.project;

import com.specagent.project.ProjectDeletionService;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectDeletionIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProjectService projects;
    @Autowired ProjectDeletionService deletion;
    @Autowired NamedParameterJdbcTemplate jdbc;

    static final AtomicBoolean DELETE_FAULT_ARMED = new AtomicBoolean(false);

    /**
     * Test-layer mid-transaction fault: a delegating template that fails the
     * {@code DELETE FROM agent_runs} statement (midway through the ordered
     * deletes) while armed. Production code exposes no test hook.
     */
    @TestConfiguration
    static class DeletionFaultConfig {
        @Bean
        @Primary
        NamedParameterJdbcTemplate faultingJdbc(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource) {
                @Override
                public int update(String sql, Map<String, ?> paramMap) {
                    throwIfArmed(sql);
                    return super.update(sql, paramMap);
                }

                @Override
                public int update(String sql, SqlParameterSource paramSource) {
                    throwIfArmed(sql);
                    return super.update(sql, paramSource);
                }

                private void throwIfArmed(String sql) {
                    if (DELETE_FAULT_ARMED.get() && sql != null && sql.contains("DELETE FROM agent_runs")) {
                        throw new RuntimeException("injected mid-delete failure");
                    }
                }
            };
        }
    }

    private int count(String sql, UUID projectId) {
        Integer v = jdbc.queryForObject(sql, Map.of("projectId", projectId), Integer.class);
        return v == null ? 0 : v;
    }

    @Test
    void deleteSuccessThenGet404AndListMissing() throws Exception {
        var p = projects.createProject("To be deleted");
        mockMvc.perform(delete("/api/v1/projects/{id}", p.id())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/projects/{id}", p.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM projects WHERE id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM routes WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM nodes WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM answers WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM answer_patches WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM agent_runs WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM context_snapshots WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM spec_snapshots WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM node_relations WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM graph_operations WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM capability_invocations WHERE project_id = :projectId", p.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM skill_activations WHERE project_id = :projectId", p.id())).isZero();
    }

    @Test
    void deleteUnknownReturns404() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void deleteDoesNotRemoveOtherProject() throws Exception {
        var keep = projects.createProject("Keep me");
        var gone = projects.createProject("Gone");
        mockMvc.perform(delete("/api/v1/projects/{id}", gone.id())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/projects/{id}", keep.id())).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM projects WHERE id = :projectId", keep.id())).isOne();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deleteRollsBackWhenMidTransactionFailureIsInjected() {
        var doomed = projects.createProject("Rollback target");
        var bystander = projects.createProject("Bystander");
        UUID route = doomed.activeRouteId();
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID answer = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID ctx = UUID.randomUUID();
        jdbc.update("INSERT INTO nodes (id, project_id, question) VALUES (:id, :projectId, 'q1')",
                Map.of("id", n1, "projectId", doomed.id()));
        jdbc.update("INSERT INTO nodes (id, project_id, question) VALUES (:id, :projectId, 'q2')",
                Map.of("id", n2, "projectId", doomed.id()));
        jdbc.update("INSERT INTO nodes (id, project_id, question) VALUES (:id, :projectId, 'other')",
                Map.of("id", UUID.randomUUID(), "projectId", bystander.id()));
        jdbc.update("INSERT INTO answers (id, project_id, route_id, node_id) VALUES (:id, :projectId, :route, :node)",
                Map.of("id", answer, "projectId", doomed.id(), "route", route, "node", n1));
        jdbc.update("INSERT INTO answer_patches (id, project_id, route_id, source_node_id, source_answer_id) VALUES (:id, :projectId, :route, :node, :answer)",
                Map.of("id", UUID.randomUUID(), "projectId", doomed.id(), "route", route, "node", n1, "answer", answer));
        jdbc.update("INSERT INTO agent_runs (id, project_id, route_id, trigger_type, status, trace, created_at) VALUES (:id, :projectId, :route, 'MANUAL', 'COMPLETED', '{}', NOW())",
                Map.of("id", run, "projectId", doomed.id(), "route", route));
        jdbc.update("INSERT INTO agent_run_events (id, run_id, sequence, phase, event_type) VALUES (:id, :run, 1, 'TEST', 'TESTED')",
                Map.of("id", UUID.randomUUID(), "run", run));
        jdbc.update("INSERT INTO agent_run_continuation_checks (run_id) VALUES (:run)",
                Map.of("run", run));
        jdbc.update("INSERT INTO agent_proposals (id, run_id, project_id, action_family) VALUES (:id, :run, :projectId, 'TEST')",
                Map.of("id", UUID.randomUUID(), "run", run, "projectId", doomed.id()));
        jdbc.update("INSERT INTO context_snapshots (id, project_id, route_id, operation_type) VALUES (:id, :projectId, :route, 'TEST')",
                Map.of("id", ctx, "projectId", doomed.id(), "route", route));
        jdbc.update("INSERT INTO spec_snapshots (id, project_id, route_id, context_snapshot_id) VALUES (:id, :projectId, :route, :ctx)",
                Map.of("id", UUID.randomUUID(), "projectId", doomed.id(), "route", route, "ctx", ctx));
        jdbc.update("INSERT INTO route_inherited_answers (branch_route_id, ordinal, node_id, answer_id, owner_route_id) VALUES (:route, 0, :node, :answer, :route)",
                Map.of("route", route, "node", n1, "answer", answer));
        jdbc.update("INSERT INTO node_relations (id, project_id, source_node_id, target_node_id, relation_type, origin) VALUES (:id, :projectId, :s, :t, 'RELATED_TO', 'TEST')",
                Map.of("id", UUID.randomUUID(), "projectId", doomed.id(), "s", n1, "t", n2));
        jdbc.update("INSERT INTO graph_operations (id, project_id, actor, type) VALUES (:id, :projectId, 'USER', 'TEST')",
                Map.of("id", UUID.randomUUID(), "projectId", doomed.id()));
        jdbc.update("INSERT INTO capability_invocations (id, invocation_key, project_id, capability_id, status) VALUES (:id, :key, :projectId, 'project.search', 'SUCCEEDED')",
                Map.of("id", UUID.randomUUID(), "key", "rb-" + UUID.randomUUID(), "projectId", doomed.id()));
        Map<String, Integer> before = snapshot(doomed.id());
        assertThat(before.get("answers")).isOne();
        assertThat(before.get("agent_runs")).isOne();
        DELETE_FAULT_ARMED.set(true);
        try {
            assertThatThrownBy(() -> deletion.deleteProject(doomed.id()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("injected mid-delete failure");
        } finally {
            DELETE_FAULT_ARMED.set(false);
        }
        // The service ran in its own transaction, so these reads observe the
        // post-rollback committed state: everything must be back.
        assertThat(snapshot(doomed.id())).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM projects WHERE id = :projectId", doomed.id())).isOne();
        assertThat(count("SELECT COUNT(*) FROM nodes WHERE project_id = :projectId", bystander.id())).isOne();
        assertThat(count("SELECT COUNT(*) FROM projects WHERE id = :projectId", bystander.id())).isOne();
        deletion.deleteProject(doomed.id());
        deletion.deleteProject(bystander.id());
        assertThat(count("SELECT COUNT(*) FROM projects WHERE id = :projectId", doomed.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM projects WHERE id = :projectId", bystander.id())).isZero();
    }

    private Map<String, Integer> snapshot(UUID projectId) {
        String[] tables = {"routes", "nodes", "answers", "answer_patches", "agent_runs",
                "agent_run_events", "agent_proposals", "context_snapshots", "spec_snapshots",
                "node_relations", "graph_operations", "capability_invocations"};
        Map<String, Integer> out = new HashMap<>();
        for (String table : tables) {
            String column = table.equals("agent_run_events") ? "run_id" : "project_id";
            String sql = table.equals("agent_run_events")
                    ? "SELECT COUNT(*) FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = :projectId)"
                    : "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :projectId";
            out.put(table, count(sql, projectId));
        }
        out.put("continuation_checks", count("SELECT COUNT(*) FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = :projectId)", projectId));
        out.put("inherited_answers", count("SELECT COUNT(*) FROM route_inherited_answers WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = :projectId)", projectId));
        return out;
    }

    @Test
    void deleteBlockedWhenRunningRunExistsAndNothingRemoved() throws Exception {
        var p = projects.createProject("Running guard");
        jdbc.update("INSERT INTO agent_runs (id, project_id, route_id, trigger_type, status, trace, created_at) VALUES (:id, :projectId, NULL, 'MANUAL', 'RUNNING', '{}' , NOW())",
                Map.of("id", UUID.randomUUID(), "projectId", p.id()));
        mockMvc.perform(delete("/api/v1/projects/{id}", p.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_HAS_RUNNING_RUNS"));
        assertThat(count("SELECT COUNT(*) FROM projects WHERE id = :projectId", p.id())).isOne();
        assertThat(count("SELECT COUNT(*) FROM routes WHERE project_id = :projectId", p.id())).isPositive();
    }
}
