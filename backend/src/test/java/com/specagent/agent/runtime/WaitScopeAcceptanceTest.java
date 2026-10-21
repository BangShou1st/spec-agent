package com.specagent.agent.runtime;

import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-database acceptance for the V4 WAIT scope contract.
 *
 * <p>The answer cycle decides against the post-state snapshot, which by design is
 * not the snapshot currently attached to the run. The scope check must accept
 * that snapshot and must keep refusing every other scope deviation.
 */
@Tag("database-integration")
@SpringBootTest
@ActiveProfiles("test")
class WaitScopeAcceptanceTest {

    @Autowired IdentityRepository identities;
    @Autowired ProjectService projects;
    @Autowired NodeService nodes;
    @Autowired RunService runService;
    @Autowired AgentRunService agentRunService;
    @Autowired ContextBuilder contextBuilder;
    @Autowired AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired WaitDependencyRepository waits;
    @Autowired JdbcTemplate jdbc;

    private final java.util.List<UUID> touchedProjects = new java.util.ArrayList<>();

    record Fixture(ActorContext actor, UUID project, UUID route, UUID root) {}

    private Fixture fixture() {
        UUID org = UUID.randomUUID(), user = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org, "wait-scope-org", "STANDARD");
        identities.insertUserIfAbsent(user, "wait-scope-owner");
        identities.upsertMembership(org, user, OrgRole.OWNER);
        ActorContext actor = ActorContext.oidc(user, org);
        UUID project, route, root;
        try (var scope = ActorContextHolder.bind(actor)) {
            Project created = projects.createProject("wait-scope-" + UUID.randomUUID());
            project = created.id();
            route = created.activeRouteId();
            Node node = nodes.createRootNode(project, route, "wait scope question", null, List.of(), true);
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
            jdbc.update("DELETE FROM workspace_run_authorizations WHERE run_id IN"
                    + " (SELECT id FROM agent_runs WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN" + " (SELECT id FROM agent_runs WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM agent_runs WHERE project_id = ?", project);
            jdbc.update("DELETE FROM context_snapshots WHERE project_id = ?", project);
            jdbc.update("DELETE FROM nodes WHERE project_id = ?", project);
            jdbc.update("UPDATE projects SET active_route_id = NULL WHERE id = ?", project);
            jdbc.update("DELETE FROM routes WHERE project_id = ?", project);
            jdbc.update("DELETE FROM projects WHERE id = ?", project);
        }
        touchedProjects.clear();
    }

    private AgentRequestEnvelope envelope(ActorContext actor, UUID runId, ContextSnapshot snapshot) {
        try (var scope = ActorContextHolder.bind(actor)) {
            AgentInputSnapshot view = snapshotBuilder.build(snapshot);
            return new AgentRequestEnvelope("agent-input.v2", runId,
                    new AgentEvent("ANSWER_SUBMITTED", snapshot.tipNodeId(), null, null), view,
                    List.of(), new DecisionBudget(2));
        }
    }

    @Test
    void thePostStateSnapshotIsAcceptedEvenWhenItIsNotTheAttachedOne() {
        Fixture f = fixture();
        AgentRun run;
        ContextSnapshot attached;
        ContextSnapshot postState;
        try (var scope = ActorContextHolder.bind(f.actor())) {
            run = runService.createQueuedRunWithInputResultForRoute(f.project(), "ANSWER_TIP",
                    f.root(), null, "wait scope answer", null, null, null, null, null);
            attached = contextBuilder.buildForRoute(f.project(), f.route(), f.root(), run.id(),
                    ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), attached.id(), "wait scope attached");
            // The answer cycle decides against a post-state snapshot of the same
            // route and tip that is not the run's currently attached snapshot.
            postState = contextBuilder.buildForRoute(f.project(), f.route(), f.root(), run.id(),
                    ContextOperationType.NORMAL);
        }
        assertThat(postState.id()).isNotEqualTo(attached.id());
        assertThat(jdbc.queryForObject("SELECT context_snapshot_id FROM agent_runs WHERE id = ?",
                UUID.class, run.id())).isEqualTo(attached.id());

        waits.requireScope(envelope(f.actor(), run.id(), postState));
    }

    @Test
    void aSnapshotOfAnotherProjectIsRefused() {
        Fixture f = fixture();
        Fixture other = fixture();
        AgentRun run;
        try (var scope = ActorContextHolder.bind(f.actor())) {
            run = runService.createQueuedRunWithInputResultForRoute(f.project(), "ANSWER_TIP",
                    f.root(), null, "wait scope answer", null, null, null, null, null);
            ContextSnapshot attached = contextBuilder.buildForRoute(f.project(), f.route(), f.root(),
                    run.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), attached.id(), "wait scope attached");
        }
        ContextSnapshot foreign;
        try (var scope = ActorContextHolder.bind(other.actor())) {
            foreign = contextBuilder.buildForRoute(other.project(), other.route(), other.root(),
                    run.id(), ContextOperationType.NORMAL);
        }
        AgentRequestEnvelope request = envelope(other.actor(), run.id(), foreign);
        assertThatThrownBy(() -> waits.requireScope(request)).hasMessage("WAIT_SCOPE_MISMATCH");
    }

    @Test
    void aSnapshotWithAnotherTipIsRefused() {
        Fixture f = fixture();
        AgentRun run;
        try (var scope = ActorContextHolder.bind(f.actor())) {
            run = runService.createQueuedRunWithInputResultForRoute(f.project(), "ANSWER_TIP",
                    f.root(), null, "wait scope answer", null, null, null, null, null);
            ContextSnapshot attached = contextBuilder.buildForRoute(f.project(), f.route(), f.root(),
                    run.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), attached.id(), "wait scope attached");
        }
        AgentRequestEnvelope request = envelope(f.actor(), run.id(),
                contextBuilder.buildForRoute(f.project(), f.route(), f.root(), run.id(),
                        ContextOperationType.NORMAL));
        // Present the snapshot with an anchor that is not the snapshot tip.
        AgentInputSnapshot view = request.snapshot();
        AgentInputSnapshot moved = new AgentInputSnapshot(view.snapshotId(), view.contextHash(),
                view.projectId(), view.routeId(), UUID.randomUUID(), view.routeContext(),
                view.lineage(), view.effectiveClaims(), view.metadata(), view.allowedSourceRefs(),
                view.availableCapabilities(), view.availableSkills(), view.capabilityResults(),
                view.relations(), view.relatedNodes(), view.autonomy());
        AgentRequestEnvelope movedRequest = new AgentRequestEnvelope("agent-input.v2", run.id(),
                request.event(), moved, List.of(), request.decisionBudget());
        assertThatThrownBy(() -> waits.requireScope(movedRequest)).hasMessage("WAIT_SCOPE_MISMATCH");
    }

    @Test
    void aSnapshotWhoseStoredHashDiffersIsRefused() {
        Fixture f = fixture();
        AgentRun run;
        try (var scope = ActorContextHolder.bind(f.actor())) {
            run = runService.createQueuedRunWithInputResultForRoute(f.project(), "ANSWER_TIP",
                    f.root(), null, "wait scope answer", null, null, null, null, null);
            ContextSnapshot attached = contextBuilder.buildForRoute(f.project(), f.route(), f.root(),
                    run.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), attached.id(), "wait scope attached");
        }
        ContextSnapshot snapshot = contextBuilder.buildForRoute(f.project(), f.route(), f.root(),
                run.id(), ContextOperationType.NORMAL);
        AgentRequestEnvelope request = envelope(f.actor(), run.id(), snapshot);
        AgentInputSnapshot view = request.snapshot();
        AgentInputSnapshot forged = new AgentInputSnapshot(view.snapshotId(),
                "0".repeat(64), view.projectId(), view.routeId(), view.anchorNodeId(),
                view.routeContext(), view.lineage(), view.effectiveClaims(), view.metadata(),
                view.allowedSourceRefs(), view.availableCapabilities(), view.availableSkills(),
                view.capabilityResults(), view.relations(), view.relatedNodes(), view.autonomy());
        AgentRequestEnvelope forgedRequest = new AgentRequestEnvelope("agent-input.v2", run.id(),
                request.event(), forged, List.of(), request.decisionBudget());
        assertThatThrownBy(() -> waits.requireScope(forgedRequest)).hasMessage("WAIT_SCOPE_MISMATCH");
    }

    @Test
    void aTerminalRunIsRefused() {
        Fixture f = fixture();
        AgentRun run;
        try (var scope = ActorContextHolder.bind(f.actor())) {
            run = runService.createQueuedRunWithInputResultForRoute(f.project(), "ANSWER_TIP",
                    f.root(), null, "wait scope answer", null, null, null, null, null);
            ContextSnapshot attached = contextBuilder.buildForRoute(f.project(), f.route(), f.root(),
                    run.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), attached.id(), "wait scope attached");
        }
        ContextSnapshot postState = contextBuilder.buildForRoute(f.project(), f.route(), f.root(),
                run.id(), ContextOperationType.NORMAL);
        jdbc.update("UPDATE agent_runs SET status = 'completed' WHERE id = ?", run.id());
        AgentRequestEnvelope request = envelope(f.actor(), run.id(), postState);
        assertThatThrownBy(() -> waits.requireScope(request)).hasMessage("WAIT_SCOPE_MISMATCH");
    }
}
