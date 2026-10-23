package com.specagent.capability;

import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.AuthorizationGuard;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.agent.action.ActionEligibilityGate;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.protocol.*;
import com.specagent.agent.runtime.*;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.retrieval.config.EmbeddingRebuilds;
import com.specagent.retrieval.config.EmbeddingSettings;
import com.specagent.retrieval.workload.RebuildLeaseCoordinator;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Real-database failure and boundary acceptance for the C16 readiness WAIT.
 *
 * <p>The terminal readiness facts are produced by the production lease coordinator
 * ({@link RebuildLeaseCoordinator}), so every assertion below is about production
 * behaviour on real PostgreSQL rows: no callback endpoint, no fixture outcome.
 */
@Tag("database-integration")
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = {
        "spec.agent.action-eligibility.mode=enforced",
        "logging.file.name=build/c16-e2e/acceptance.log"
})
class C16ReadinessFailureAcceptanceTest {

    @Autowired IdentityRepository identities;
    @Autowired EmbeddingSettings settings;
    @Autowired EmbeddingRebuilds rebuilds;
    @Autowired ProjectService projects;
    @Autowired NodeService nodes;
    @Autowired RunService runService;
    @Autowired AgentRunService agentRunService;
    @Autowired ContextBuilder contextBuilder;
    @Autowired AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired ActionEligibilityGate gate;
    @Autowired WaitDependencyService waitDependencies;
    @Autowired CapabilityRuntime capabilityRuntime;
    @Autowired ContinuationDispatchService continuationDispatch;
    @Autowired RebuildLeaseCoordinator leases;
    @Autowired RebuildWaitRepository rebuildWaitRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired AuthorizationGuard authorization;
    @Autowired PlatformTransactionManager transactions;

    @SpyBean AgentDecisionEngine decisionEngine;

    private final java.util.List<UUID> touchedProjects = new java.util.ArrayList<>();

    record Fixture(ActorContext actor, UUID project, UUID rootNode, UUID route, String profileId) {}

    private Fixture fixture() {
        UUID org = UUID.randomUUID(), user = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org, "c16-fail-org", "STANDARD");
        identities.insertUserIfAbsent(user, "c16-fail-owner");
        identities.upsertMembership(org, user, OrgRole.OWNER);
        ActorContext actor = ActorContext.oidc(user, org);
        UUID project, route, root;
        String profileId;
        try (var scope = ActorContextHolder.bind(actor)) {
            var config = new EmbeddingSettings.Config("OPENAI_COMPATIBLE",
                    "http://127.0.0.1:18399/v1", "c16-fail-" + org, 30, 8, "raw-text.v1");
            settings.save(new EmbeddingSettings.Draft(config, "c16-fail-key", 0L));
            profileId = settings.register(config, 3, null, 1).profileId();
            Project created = projects.createProject("c16-fail-" + UUID.randomUUID());
            project = created.id();
            route = created.activeRouteId();
            Node node = nodes.createRootNode(project, route, "c16 failure source", null, List.of(), true);
            root = node.id();
        }
        touchedProjects.add(project);
        return new Fixture(actor, project, root, route, profileId);
    }

    private AgentRun createRun(Fixture f, String text) {
        try (var scope = ActorContextHolder.bind(f.actor())) {
            return runService.createQueuedRunWithInputResultForRoute(f.project(), "ANSWER_TIP",
                    f.rootNode(), null, text, null, null, null, null, null);
        }
    }

    /** Creates the real generation and the real pending capability binding. */
    private record Prepared(Fixture f, UUID generation, AgentRun owner, AgentRun waiter,
                            CapabilityResult invocation) {}

    private Prepared prepare(Fixture f) {
        UUID generation;
        try (var scope = ActorContextHolder.bind(f.actor())) {
            rebuilds.start(f.project(), f.profileId());
            generation = jdbc.queryForObject("SELECT id FROM embedding_rebuilds WHERE corpus_id = ?",
                    UUID.class, f.project());
        }
        AgentRun owner = createRun(f, "c16 failure owner");
        var invoked = capabilityRuntime.invokeDeferredAllowed("run:" + owner.id() + ":proposal:c16",
                RetrievalAwaitGenerationCapability.ID, f.project(), owner.id(), Map.of());
        AgentRun waiter = createRun(f, "c16 failure waiter");
        return new Prepared(f, generation, owner, waiter, invoked);
    }

    /** Freezes the projection and subscribes the waiting run to the pending invocation. */
    private void subscribe(Prepared p) {
        try (var scope = ActorContextHolder.bind(p.f().actor())) {
            ContextSnapshot snapshot = contextBuilder.buildForRoute(p.f().project(), p.f().route(),
                    p.f().rootNode(), p.waiter().id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(p.waiter().id(), snapshot.id(), "c16 failure context");
            AgentRequestEnvelope base = snapshotBuilder.buildEnvelope(p.waiter().id(), snapshot,
                    new AgentEvent("CONTINUE", p.f().rootNode(), null, null), new DecisionBudget(1));
            AgentRequestEnvelope v4 = gate.prepareDecisionRequest(base);
            ActionProposal proposal = new ActionProposal("WAIT",
                    Map.of("dependencyRef", p.invocation().invocationId().toString()),
                    v4.pendingDependencies().snapshotId(), v4.pendingDependencies().contextHash(),
                    List.of(), UUID.randomUUID(), p.waiter().id().toString(), List.of());
            // The production SPI registers the subscription inside the caller's
            // transaction, exactly as the decision tail does.
            new TransactionTemplate(transactions).executeWithoutResult(tx ->
                    waitDependencies.subscribe(v4, proposal));
        }
    }

    private void recover() {
        try (var scope = ActorContextHolder.bind(pActor)) {
            continuationDispatch.recoverPending(100);
        }
    }

    private ActorContext pActor;

    @AfterEach
    void tearDown() {
        for (UUID project : touchedProjects) {
            jdbc.update("UPDATE agent_runs SET produced_answer_id = NULL, produced_patch_id = NULL,"
                    + " produced_spec_snapshot_id = NULL, produced_node_id = NULL,"
                    + " input_node_id = NULL, route_id = NULL, context_snapshot_id = NULL"
                    + " WHERE project_id = ?", project);
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN"
                    + " (SELECT id FROM agent_runs WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM agent_run_wait_subscriptions WHERE project_id = ?", project);
            jdbc.update("DELETE FROM agent_run_dependency_projections WHERE run_id IN"
                    + " (SELECT id FROM agent_runs WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM capability_rebuild_waits WHERE invocation_id IN"
                    + " (SELECT id FROM capability_invocations WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM capability_invocations WHERE project_id = ?", project);
            jdbc.update("DELETE FROM workspace_run_authorizations WHERE run_id IN"
                    + " (SELECT id FROM agent_runs WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN"
                    + " (SELECT id FROM agent_runs WHERE project_id = ?)", project);
            jdbc.update("DELETE FROM agent_runs WHERE project_id = ?", project);
            jdbc.update("DELETE FROM spec_snapshots WHERE project_id = ?", project);
            jdbc.update("DELETE FROM context_snapshots WHERE project_id = ?", project);
            jdbc.update("DELETE FROM retrieval_source_projections WHERE project_id = ?", project);
            jdbc.update("DELETE FROM node_relations WHERE project_id = ?", project);
            jdbc.update("UPDATE answer_patches SET source_answer_id = NULL WHERE project_id = ?", project);
            jdbc.update("DELETE FROM answer_patches WHERE project_id = ?", project);
            jdbc.update("DELETE FROM answers WHERE project_id = ?", project);
            jdbc.update("DELETE FROM retrieval_entries WHERE project_id = ?", project);
            jdbc.update("DELETE FROM embedding_rebuild_outcomes WHERE project_id = ?", project);
            jdbc.update("DELETE FROM embedding_rebuilds WHERE corpus_id = ?", project);
            jdbc.update("DELETE FROM retrieval_index_jobs WHERE corpus_id = ?", project);
            jdbc.update("DELETE FROM retrieval_index_heads WHERE corpus_id = ?", project);
            // retrieval_scope_grants.index_generation references the generation
            // with no ON DELETE action (V49): the grant has to go first or the
            // generation delete is rejected. Same ordering as the sibling
            // C16CrossProcessWorkerAcceptanceTest.
            jdbc.update("DELETE FROM retrieval_scope_grants WHERE corpus_id = ?", project);
            jdbc.update("DELETE FROM retrieval_index_generations WHERE corpus_id = ?", project);
            jdbc.update("DELETE FROM nodes WHERE project_id = ?", project);
            jdbc.update("UPDATE projects SET active_route_id = NULL WHERE id = ?", project);
            jdbc.update("DELETE FROM routes WHERE project_id = ?", project);
            jdbc.update("DELETE FROM projects WHERE id = ?", project);
        }
        touchedProjects.clear();
    }

    @Test
    void failedGenerationSettlesTheWaitingRunAsFailed() {
        Fixture f = fixture();
        Prepared p = prepare(f);
        pActor = f.actor();
        subscribe(p);

        // The production coordinator commits the terminal failure fact.
        var token = leases.claim(p.generation()).orElseThrow();
        assertThat(leases.fail(token, "EMBEDDING_TIMEOUT")).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_rebuild_outcomes WHERE generation_id = ?",
                String.class, p.generation())).isEqualTo("FAILED");

        recover();

        assertThat(jdbc.queryForObject("SELECT status FROM capability_invocations WHERE id = ?",
                String.class, p.invocation().invocationId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT state FROM agent_run_wait_subscriptions WHERE wait_run_id = ?",
                String.class, p.waiter().id())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_continuation_checks WHERE run_id = ?",
                Integer.class, p.waiter().id())).isEqualTo(1);
    }

    @Test
    void missingGenerationIsAnExplicitFailureAndNeverAPendingWait() {
        Fixture f = fixture();
        pActor = f.actor();
        // No rebuild was ever requested for this project.
        AgentRun owner = createRun(f, "c16 no generation");
        var invoked = capabilityRuntime.invokeDeferredAllowed("run:" + owner.id() + ":proposal:c16",
                RetrievalAwaitGenerationCapability.ID, f.project(), owner.id(), Map.of());

        assertThat(invoked.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(jdbc.queryForObject("SELECT result::text FROM capability_invocations WHERE id = ?",
                String.class, invoked.invocationId())).contains("REBUILD_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM capability_rebuild_waits WHERE invocation_id = ?",
                Integer.class, invoked.invocationId())).isZero();
    }

    @Test
    void duplicateCompletionEventSettlesOnlyOnceAndKeepsOneOutboxGeneration() {
        Fixture f = fixture();
        Prepared p = prepare(f);
        pActor = f.actor();
        subscribe(p);

        var token = leases.claim(p.generation()).orElseThrow();
        leases.finish(token, 1, 1, true);

        recover();
        String first = jdbc.queryForObject("SELECT request_generation::text FROM agent_run_continuation_checks"
                + " WHERE run_id = ?", String.class, p.waiter().id());
        assertThat(jdbc.queryForObject("SELECT status FROM capability_invocations WHERE id = ?",
                String.class, p.invocation().invocationId())).isEqualTo("SUCCEEDED");

        // A second delivery of the same committed fact must not create a second wake-up.
        recover();
        recover();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_continuation_checks WHERE run_id = ?",
                Integer.class, p.waiter().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_generation::text FROM agent_run_continuation_checks"
                + " WHERE run_id = ?", String.class, p.waiter().id())).isEqualTo(first);
    }

    @Test
    void completionAfterTheTimeoutDoesNotRewriteTheTerminalState() {
        Fixture f = fixture();
        Prepared p = prepare(f);
        pActor = f.actor();
        subscribe(p);

        var token = leases.claim(p.generation()).orElseThrow();
        leases.finish(token, 1, 1, true);
        // The deadline expires before the scanner observes the committed fact.
        jdbc.update("UPDATE capability_invocations SET pending_deadline = clock_timestamp() - interval '1 second'"
                + " WHERE id = ?", p.invocation().invocationId());

        recover();

        String status = jdbc.queryForObject("SELECT status FROM capability_invocations WHERE id = ?",
                String.class, p.invocation().invocationId());
        assertThat(status).as("the timeout winner keeps the terminal state").isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT result::text FROM capability_invocations WHERE id = ?",
                String.class, p.invocation().invocationId())).contains("DEPENDENCY_TIMEOUT");
    }

    @Test
    void aNewRetryAttemptDoesNotLetAnOldGenerationEventWakeTheWaiter() {
        Fixture f = fixture();
        Prepared p = prepare(f);
        pActor = f.actor();
        subscribe(p);

        // The first attempt fails; the retry starts a new readiness revision.
        var token = leases.claim(p.generation()).orElseThrow();
        leases.fail(token, "EMBEDDING_TIMEOUT");
        try (var scope = ActorContextHolder.bind(f.actor())) {
            rebuilds.retry(p.generation());
        }
        assertThat(jdbc.queryForObject("SELECT readiness_revision::text FROM embedding_rebuilds WHERE id = ?",
                String.class, p.generation())).isEqualTo("1");

        recover();

        // The binding still points at revision 0; the retry's revision 1 must not settle it.
        String status = jdbc.queryForObject("SELECT status FROM capability_invocations WHERE id = ?",
                String.class, p.invocation().invocationId());
        assertThat(status).as("an old revision must not settle the new attempt").isNotEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT readiness_revision::text FROM capability_rebuild_waits"
                + " WHERE invocation_id = ?", String.class, p.invocation().invocationId())).isEqualTo("0");
    }

    @Test
    void aWaiterFromAnotherProjectCannotSubscribeToTheInvocation() {
        Fixture f = fixture();
        Fixture other = fixture();
        pActor = f.actor();
        Prepared p = prepare(f);
        AgentRun foreign = createRun(other, "c16 foreign waiter");

        try (var scope = ActorContextHolder.bind(other.actor())) {
            ContextSnapshot snapshot = contextBuilder.buildForRoute(other.project(), other.route(),
                    other.rootNode(), foreign.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(foreign.id(), snapshot.id(), "c16 foreign context");
            AgentRequestEnvelope base = snapshotBuilder.buildEnvelope(foreign.id(), snapshot,
                    new AgentEvent("CONTINUE", other.rootNode(), null, null), new DecisionBudget(1));
            AgentRequestEnvelope v4 = gate.prepareDecisionRequest(base);
            assertThat(v4.pendingDependencies().dependencies())
                    .as("a foreign project must not see the pending dependency at all")
                    .isEmpty();
        }
    }

    @Test
    void aRevokedOrganizationCannotBeWokenByALateCompletion() {
        Fixture f = fixture();
        Prepared p = prepare(f);
        pActor = f.actor();
        subscribe(p);

        var token = leases.claim(p.generation()).orElseThrow();
        leases.finish(token, 1, 1, true);

        // The delegated user loses the organization membership before the scan.
        jdbc.update("UPDATE organization_members SET status = 'DISABLED' WHERE org_id = ? AND user_id = ?",
                f.actor().orgId(), f.actor().userId());

        recover();

        String status = jdbc.queryForObject("SELECT status FROM capability_invocations WHERE id = ?",
                String.class, p.invocation().invocationId());
        assertThat(status).as("a revoked owner must not be woken by a late completion").isNotEqualTo("SUCCEEDED");
    }
}
