package com.specagent.agent.runtime;

import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database acceptance for a continuation check whose owner run is not
 * terminal yet.
 *
 * <p>The check must close out for this generation instead of being reclaimed
 * and retried on every poll, and the continuation must still be created once
 * the owner run reaches a terminal state.
 */
@Tag("database-integration")
@SpringBootTest
@ActiveProfiles("test")
class ContinuationOwnerPendingAcceptanceTest {

    @Autowired IdentityRepository identities;
    @Autowired ProjectService projects;
    @Autowired NodeService nodes;
    @Autowired RunService runService;
    @Autowired AgentRunService agentRunService;
    @Autowired ContinuationCheckRepository checks;
    @Autowired AgentRunTerminalizationService terminalization;
    @Autowired ContinuationDispatchService dispatch;
    @Autowired JdbcTemplate jdbc;

    private final java.util.List<UUID> touchedProjects = new java.util.ArrayList<>();

    record Fixture(ActorContext actor, UUID project, UUID route, UUID root) {}

    private Fixture fixture() {
        UUID org = UUID.randomUUID(), user = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org, "continuation-owner-org", "STANDARD");
        identities.insertUserIfAbsent(user, "continuation-owner-user");
        identities.upsertMembership(org, user, OrgRole.OWNER);
        ActorContext actor = ActorContext.oidc(user, org);
        UUID project, route, root;
        try (var scope = ActorContextHolder.bind(actor)) {
            Project created = projects.createProject("continuation-owner-" + UUID.randomUUID());
            project = created.id();
            route = created.activeRouteId();
            Node node = nodes.createRootNode(project, route, "continuation owner question", null,
                    List.of(), true);
            root = node.id();
        }
        touchedProjects.add(project);
        return new Fixture(actor, project, route, root);
    }

    @AfterEach
    void cleanUp() {
        for (UUID project : touchedProjects) {
            jdbc.update("UPDATE agent_runs SET context_snapshot_id = NULL, route_id = NULL,"
                    + " input_node_id = NULL WHERE project_id = ?", project);
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN"
                    + " (SELECT id FROM agent_runs WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM workspace_run_authorizations WHERE run_id IN"
                    + " (SELECT id FROM agent_runs WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN"
                    + " (SELECT id FROM agent_runs WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM capability_invocations WHERE project_id = ?", project);
            jdbc.update("DELETE FROM agent_runs WHERE project_id = ?", project);
            jdbc.update("DELETE FROM context_snapshots WHERE project_id = ?", project);
            jdbc.update("DELETE FROM nodes WHERE project_id = ?", project);
            jdbc.update("UPDATE projects SET active_route_id = NULL WHERE id = ?", project);
            jdbc.update("DELETE FROM routes WHERE project_id = ?", project);
            jdbc.update("DELETE FROM projects WHERE id = ?", project);
        }
        touchedProjects.clear();
    }

    @Test
    void aNonTerminalOwnerClosesTheCheckForThisGenerationAndStillContinuesLater() {
        Fixture f = fixture();
        UUID owner;
        try (var scope = ActorContextHolder.bind(f.actor())) {
            owner = runService.createQueuedRunWithInputResultForRoute(f.project(), "ANSWER_TIP",
                    f.root(), null, "continuation owner answer", null, null, null, null, null).id();
            // The production request path of a continuation check for this run.
            checks.request(owner);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM agent_runs WHERE id = ?",
                String.class, owner)).isEqualTo("created");

        try (var scope = ActorContextHolder.bind(f.actor())) {
            // The dispatcher must not raise, and must close the check for this generation.
            dispatch.recoverPending(50);
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT processed_at, request_generation FROM agent_run_continuation_checks WHERE run_id = ?",
                owner);
        assertThat(row.get("processed_at")).as("the check must close out for this generation").isNotNull();
        assertThat(((Number) row.get("request_generation")).longValue())
                .as("a closed check must not be re-requested by the retry loop").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, owner)).isZero();

        // The owner run owns a settled capability invocation: that durable fact is
        // what makes a continuation eligible once the run is terminal.
        jdbc.update("INSERT INTO capability_invocations(id,invocation_key,project_id,run_id,"
                + "capability_id,arguments,status,result,created_at,completed_at)"
                + " VALUES (?,?,?,?,'memory.read','{}'::jsonb,'SUCCEEDED',"
                + "'{\"content\":{\"ok\":true}}'::jsonb,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), "continuation-owner-fact-" + UUID.randomUUID(), f.project(), owner);

        // The owner run reaches a terminal state: the terminalization re-requests the
        // check, and the continuation is still created.
        try (var scope = ActorContextHolder.bind(f.actor())) {
            terminalization.completeWithCheck(owner, AgentRunStatus.COMPLETED, "continuation owner parked");
            dispatch.recoverPending(50);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, owner))
                .as("a still recoverable continuation must not be dropped").isEqualTo(1);
    }
}
