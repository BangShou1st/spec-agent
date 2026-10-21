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
import com.specagent.common.BrainConnectionSettings;
import com.specagent.retrieval.config.EmbeddingRebuilds;
import com.specagent.retrieval.config.EmbeddingSettings;
import com.specagent.retrieval.config.EmbeddingTransport;
import com.specagent.retrieval.protocol.HelpCorpusJobs;
import com.specagent.retrieval.protocol.RetrievalSourceJobs;
import com.specagent.retrieval.protocol.RetrievalIndexJobs;
import com.specagent.retrieval.protocol.RetrievalStore;
import com.specagent.retrieval.workload.RebuildLeaseCoordinator;
import com.specagent.access.OrganizationConfigurationScope;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Real end-to-end acceptance of the C16 retrieval readiness WAIT loop.
 *
 * <p>Everything that decides the outcome is production code against a real
 * PostgreSQL database: the rebuild is created through the production service,
 * executed by the production worker over a real Python retrieval process, the
 * readiness fact is committed by the production lease coordinator, the
 * capability binding, the WAIT subscription, the completion CAS, the
 * continuation outbox and the continuation itself are all production paths.
 *
 * <p>The only substituted component is the embedding provider transport (a
 * paid model is never called) and the decision engine, which is scripted for
 * the single WAIT proposal the deterministic fake engine cannot express.
 */
@Tag("database-integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// The V4 WAIT path only exists in enforced mode; the shared test profile
// deliberately keeps the gate in shadow so ordinary suites stay V2.
@org.springframework.test.context.TestPropertySource(properties = {
        "spec.agent.action-eligibility.mode=enforced",
        "logging.file.name=build/c16-e2e/app.log"
})
class C16RetrievalAwaitGenerationEndToEndTest {

    @Autowired IdentityRepository identities;
    @Autowired EmbeddingSettings settings;
    @Autowired EmbeddingRebuilds rebuilds;
    @Autowired ProjectService projects;
    @Autowired NodeService nodes;
    @Autowired com.specagent.workspace.answer.AnswerService answers;
    @Autowired RunService runService;
    @Autowired AgentRunService agentRunService;
    @Autowired ContextBuilder contextBuilder;
    @Autowired AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired ActionEligibilityGate gate;
    @Autowired WaitDependencyService waitDependencies;
    @Autowired CapabilityRuntime capabilityRuntime;
    @Autowired ContinuationDispatchService continuationDispatch;
    @Autowired com.specagent.agent.runtime.DecisionExecutionService decisionExecution;
    @Autowired RunWorker runWorker;
    @Autowired WorkspaceRunActorRepository runActors;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired AuthorizationGuard authorization;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RebuildLeaseCoordinator leases;
    @Autowired RetrievalIndexJobs jobs;
    @Autowired RetrievalSourceJobs sourceJobs;
    @Autowired HelpCorpusJobs helpJobs;
    @Autowired OrganizationConfigurationScope configurationScope;
    @Autowired RetrievalStore store;
    @Autowired RebuildWaitRepository rebuildWaitRepository;
    @Autowired com.specagent.common.BrainConnectionSettings brainConnection;

    @SpyBean AgentDecisionEngine decisionEngine;
    @MockBean EmbeddingTransport transport;

    @org.springframework.boot.test.web.server.LocalServerPort int port;

    private final java.util.List<UUID> touchedProjects = new java.util.ArrayList<>();
    private Process python;
    private Path pythonLogFile;

    record Fixture(ActorContext actor, UUID project, UUID rootNode, UUID route, String profileId) {}

    private Fixture fixture() {
        UUID org = UUID.randomUUID(), user = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org, "c16-e2e-org", "STANDARD");
        identities.insertUserIfAbsent(user, "c16-e2e-owner");
        identities.upsertMembership(org, user, OrgRole.OWNER);
        ActorContext actor = ActorContext.oidc(user, org);
        UUID project, route, root;
        String profileId;
        try (var scope = ActorContextHolder.bind(actor)) {
            var config = new EmbeddingSettings.Config("OPENAI_COMPATIBLE",
                    "http://127.0.0.1:18399/v1", "c16-model-" + org, 30, 8, "raw-text.v1");
            settings.save(new EmbeddingSettings.Draft(config, "c16-fixture-key", 0L));
            var profile = settings.register(config, 3, null, 1);
            profileId = profile.profileId();
            Project created = projects.createProject("c16-e2e-" + UUID.randomUUID());
            project = created.id();
            route = created.activeRouteId();
            Node node = nodes.createRootNode(project, route, "c16 retrieval readiness source", null,
                    List.of(), true);
            root = node.id();
            // The continuation cycle may attach a child to the tip question; the graph
            // invariant requires a finalized effective answer first.
            answers.finalizeAnswerWithSelections(project, route, root, List.of(),
                    "c16 answered context", "user");
        }
        touchedProjects.add(project);
        return new Fixture(actor, project, root, route, profileId);
    }

    /** Starts the real Python retrieval service in broker mode. */
    private String startPythonBrain() throws Exception {
        int brainPort;
        try (var socket = new java.net.ServerSocket(0)) { brainPort = socket.getLocalPort(); }
        Path brainDirectory = Path.of("../agent-brain").toAbsolutePath().normalize();
        Path repositoryRoot = brainDirectory.getParent();
        String pythonCommand = com.specagent.testing.PythonExecutable.locate(repositoryRoot);
        // A bare command name means "resolve it from PATH": CI installs the brain
        // with the runner's own Python instead of a repository virtualenv.
        if (Path.of(pythonCommand).isAbsolute()) {
            assertThat(Files.isRegularFile(Path.of(pythonCommand))).isTrue();
        }
        var builder = new ProcessBuilder(pythonCommand, "-m", "uvicorn",
                "spec_agent_brain.app:create_app", "--factory", "--host", "127.0.0.1",
                "--port", Integer.toString(brainPort));
        builder.directory(Path.of("../agent-brain").toFile());
        builder.environment().put("SPEC_AGENT_BRAIN_MODEL_MODE", "broker");
        builder.environment().put("SPEC_AGENT_INTERNAL_BROKER_URL",
                "http://127.0.0.1:" + port + "/internal/v1/model-inference");
        builder.environment().put("SPEC_AGENT_BRAIN_INTERNAL_SECRET", brainConnection.getInternalSecret());
        Path log = Files.createTempFile("c16-e2e-python-", ".log");
        pythonLogFile = log;
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        python = builder.start();
        String origin = "http://127.0.0.1:" + brainPort;
        boolean ready = false;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            while (System.nanoTime() < until && python.isAlive()) {
                try {
                    ready = http.send(java.net.http.HttpRequest
                                    .newBuilder(java.net.URI.create(origin + "/health"))
                                    .timeout(java.time.Duration.ofSeconds(1)).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                    if (ready) break;
                } catch (java.io.IOException unavailable) { }
                Thread.sleep(50);
            }
        }
        assertThat(ready).as("isolated Python startup: %s", Files.readString(log)).isTrue();
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            var probe = http.send(java.net.http.HttpRequest
                            .newBuilder(java.net.URI.create(origin + "/internal/v1/retrieval/health"))
                            .timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            retrievalHealth = probe.statusCode() + " " + probe.body();
        }
        return origin;
    }

    private String retrievalHealth = "(not probed)";

    private AgentRun createRun(Fixture f, String text) {
        try (var scope = ActorContextHolder.bind(f.actor())) {
            return runService.createQueuedRunWithInputResultForRoute(f.project(), "ANSWER_TIP",
                    f.rootNode(), null, text, null, null, null, null, null);
        }
    }

    private String pythonLog() {
        if (pythonLogFile == null) return "(no python log)";
        try { return Files.readString(pythonLogFile); }
        catch (Exception unreadable) { return "(python log unreadable: " + unreadable + ")"; }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (python != null) {
            python.descendants().toList().forEach(ProcessHandle::destroyForcibly);
            python.destroyForcibly();
            python.waitFor(10, TimeUnit.SECONDS);
            python = null;
        }
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
            jdbc.update("DELETE FROM retrieval_index_generations WHERE corpus_id = ?", project);
            jdbc.update("DELETE FROM nodes WHERE project_id = ?", project);
            jdbc.update("UPDATE projects SET active_route_id = NULL WHERE id = ?", project);
            jdbc.update("DELETE FROM routes WHERE project_id = ?", project);
            jdbc.update("DELETE FROM projects WHERE id = ?", project);
        }
        touchedProjects.clear();
    }

    @Test
    void realRebuildReadinessWakesTheWaitingRunAndResumesTheChain() throws Exception {
        Fixture f = fixture();
        String origin = startPythonBrain();

        // ── 1. the production service creates the real generation ─────────────
        UUID generation;
        try (var scope = ActorContextHolder.bind(f.actor())) {
            rebuilds.start(f.project(), f.profileId());
            generation = jdbc.queryForObject(
                    "SELECT id FROM embedding_rebuilds WHERE corpus_id = ?", UUID.class, f.project());
        }
        assertThat(generation).isNotNull();
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_rebuilds WHERE id = ?",
                String.class, generation)).isEqualTo("QUEUED");

        // ── 2. the owner run registers the deferred capability invocation ─────
        AgentRun owner = createRun(f, "c16 owner run");
        var invoked = capabilityRuntime.invokeDeferredAllowed(
                "run:" + owner.id() + ":proposal:c16-owner",
                RetrievalAwaitGenerationCapability.ID, f.project(), owner.id(), Map.of());
        assertThat(invoked.status()).isEqualTo(CapabilityResult.Status.PENDING);

        // The Host bound the concrete generation and readiness attempt.
        Map<String, Object> binding = named.queryForMap(
                "SELECT b.generation_id, b.readiness_revision FROM capability_rebuild_waits b"
                        + " WHERE b.invocation_id = :id", Map.of("id", invoked.invocationId()));
        assertThat(binding.get("generation_id")).isEqualTo(generation);
        assertThat(((Number) binding.get("readiness_revision")).longValue()).isZero();

        // ── 3. the waiting run freezes the projection and subscribes ─────────
        AgentRun waiter = createRun(f, "c16 waiter run");
        try (var scope = ActorContextHolder.bind(f.actor())) {
            ContextSnapshot snapshot = contextBuilder.buildForRoute(f.project(), f.route(),
                    f.rootNode(), waiter.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(waiter.id(), snapshot.id(), "c16 e2e context");
            AgentRequestEnvelope base = snapshotBuilder.buildEnvelope(waiter.id(), snapshot,
                    new AgentEvent("CONTINUE", f.rootNode(), null, null), new DecisionBudget(1));
            AgentRequestEnvelope v4 = gate.prepareDecisionRequest(base);
            assertThat(v4.protocolVersion()).isEqualTo(AgentProtocol.INPUT_PROTOCOL_VERSION_V4);
            assertThat(v4.pendingDependencies()).isNotNull();
            assertThat(v4.pendingDependencies().dependencies())
                    .extracting(PendingDependencyProjection.Dependency::invocationId)
                    .contains(invoked.invocationId());

            ActionProposal proposal = new ActionProposal("WAIT",
                    Map.of("dependencyRef", invoked.invocationId().toString()),
                    v4.pendingDependencies().snapshotId(), v4.pendingDependencies().contextHash(),
                    List.of(), UUID.randomUUID(), waiter.id().toString(), List.of());
            stubWaitProposal(waiter.id(), proposal, v4);

            // The production decision tail: subscribe + terminalize atomically.
            var result = decisionExecution.execute(snapshot, v4,
                    new ActionExecutionContext(waiter.id(), f.project(), f.route(), snapshot.id(),
                            f.rootNode(), null, null),
                    "c16", ">", Map.of());
            assertThat(result.outcome()).isEqualTo("waiting_dependency");
        }

        assertThat(jdbc.queryForObject("SELECT state FROM agent_run_wait_subscriptions WHERE wait_run_id = ?",
                String.class, waiter.id())).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT status FROM agent_runs WHERE id = ?",
                String.class, waiter.id())).isEqualTo("completed");

        // ── 4. the real worker rebuilds and commits the readiness fact ───────
        when(transport.remote(any(), anyString(), anyList())).thenAnswer(call ->
                ((List<?>) call.getArgument(2)).stream().map(input -> new double[]{1, 0, 0}).toList());
        var connection = new BrainConnectionSettings() {
            public String getBaseUrl() { return origin; }
            public String getInternalSecret() { return brainConnection.getInternalSecret(); }
        };
        var worker = new EmbeddingRebuilds(jdbc, settings, jobs, store, sourceJobs, helpJobs,
                connection, origin, true, authorization, configurationScope, leases);
        worker.recover();
        worker.tick();
        worker.tick();

        var rebuildRow = jdbc.queryForMap("SELECT state, error_code, processed, total, execution_epoch"
                + " FROM embedding_rebuilds WHERE id = ?", generation);
        assertThat(rebuildRow.get("state"))
                .as("rebuild outcome: %s ; retrieval health: %s ; python log: %s", rebuildRow,
                        retrievalHealth, pythonLog())
                .isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM embedding_rebuild_outcomes WHERE generation_id = ?"
                        + " AND readiness_revision = 0", String.class, generation)).isEqualTo("READY");

        // ── 5. the completion scanner observes the committed fact and CAS ────
        try {
            assertThat(rebuildWaitRepository.pending(100))
                    .as("the bounded readiness scan must observe the committed fact")
                    .contains(invoked.invocationId());
        } catch (org.springframework.jdbc.BadSqlGrammarException ex) {
            throw new IllegalStateException("readiness scan failed: " + ex.getCause(), ex.getCause());
        }
        // The owner run terminalizes once its capability proposal was dispatched,
        // exactly as the production decision tail does.
        try (var scope = ActorContextHolder.bind(f.actor())) {
            agentRunService.complete(owner.id(), AgentRunStatus.COMPLETED, "c16 owner proposal dispatched");
            continuationDispatch.recoverPending(100);
        }

        assertThat(jdbc.queryForObject("SELECT status FROM capability_invocations WHERE id = ?",
                String.class, invoked.invocationId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT state FROM agent_run_wait_subscriptions WHERE wait_run_id = ?",
                String.class, waiter.id())).isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_run_continuation_checks WHERE run_id = ?",
                Integer.class, waiter.id())).as("the waiting run must have a continuation outbox row")
                .isGreaterThanOrEqualTo(1);

        // The completed result carries the concrete generation identity.
        String content = jdbc.queryForObject("SELECT result::text FROM capability_invocations WHERE id = ?",
                String.class, invoked.invocationId());
        assertThat(content).contains("READY").contains(generation.toString());

        // ── 6. the continuation is really consumed and a child run appears ───
        try (var scope = ActorContextHolder.bind(f.actor())) {
            continuationDispatch.recoverPending(100);
        }
        var child = jdbc.queryForList(
                "SELECT id, status, cycle_index FROM agent_runs WHERE parent_run_id = ?",
                waiter.id());
        assertThat(child).as("the WAIT must create exactly one continuation child").hasSize(1);
        assertThat(((Number) child.getFirst().get("cycle_index")).intValue()).isEqualTo(1);

        // ── 7. the child run reaches a terminal state in the state machine ──
        UUID childId = (UUID) child.getFirst().get("id");
        AgentRun claimedChild = null;
        for (int attempt = 0; attempt < 5 && claimedChild == null; attempt++) {
            var claim = runService.claimNextContinue();
            if (claim.isEmpty()) break;
            if (claim.get().id().equals(childId)) {
                claimedChild = claim.get();
            } else {
                // The owner run also owns a continuation child; drive it as well.
                runWorker.executeRun(claim.get());
            }
        }
        assertThat(claimedChild).as("the waiting run's continuation child must be claimable").isNotNull();
        runWorker.executeRun(claimedChild);
        String childStatus = jdbc.queryForObject("SELECT status FROM agent_runs WHERE id = ?",
                String.class, childId);
        assertThat(childStatus).as("the continuation must reach a terminal state").isIn("completed", "failed");
        // The resumed child carries the exact settled observation.
        var projection = jdbc.queryForObject(
                "SELECT payload FROM agent_run_dependency_projections WHERE run_id = ?",
                String.class, childId);
        assertThat(projection).contains(invoked.invocationId().toString());
    }

    private void stubWaitProposal(UUID runId, ActionProposal proposal, AgentRequestEnvelope request) {
        var response = new AgentResponseEnvelope(AgentProtocol.DECISION_PROTOCOL_VERSION_V4, request.runId(),
                null,
                new ObservationView(List.of(), List.of(), List.of(), List.of()), proposal,
                new UsageView(1, List.of()), Map.of(), request.actionEligibility().version(),
                request.actionEligibility().basisHash(), List.of());
        // The deterministic fake engine cannot express a WAIT proposal, so the WAIT
        // decision is scripted. Every other decision (the resumed continuation cycle)
        // answers with the read-only RESPOND_TO_USER family, which is eligible in the
        // V4 mask and mutates no graph, so the resumed run converges instead of
        // failing on unrelated graph preconditions.
        org.mockito.Mockito.doAnswer(call -> {
            AgentRequestEnvelope envelope = call.getArgument(0);
            if (runId.equals(envelope.runId())) return response;
            return respondToUser(envelope);
        }).when(decisionEngine).runDecision(any());
    }

    private AgentResponseEnvelope respondToUser(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        var proposal = new ActionProposal("RESPOND_TO_USER",
                Map.of("message", "c16 continuation resumed with the committed readiness fact"),
                snapshotId, request.snapshot().contextHash(), List.of(), UUID.randomUUID(),
                request.runId().toString(), List.of());
        boolean v4 = AgentProtocol.INPUT_PROTOCOL_VERSION_V4.equals(request.protocolVersion());
        String version = v4 ? AgentProtocol.DECISION_PROTOCOL_VERSION_V4
                : AgentProtocol.DECISION_PROTOCOL_VERSION;
        String eligibilityVersion = request.actionEligibility() == null ? null
                : request.actionEligibility().version();
        String basisHash = request.actionEligibility() == null ? null
                : request.actionEligibility().basisHash();
        return new AgentResponseEnvelope(version, request.runId(), null,
                new ObservationView(List.of(), List.of(), List.of(), List.of()), proposal,
                new UsageView(1, List.of()), Map.of(), eligibilityVersion, basisHash, List.of());
    }
}
