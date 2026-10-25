package com.specagent.capability;

import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.agent.action.ActionEligibilityGate;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-process acceptance for the C16 readiness WAIT.
 *
 * <p>The workers are <b>independent operating-system processes</b> running the
 * shipped application against the same isolated PostgreSQL database. This test
 * JVM keeps every background worker switched off, so every completion CAS,
 * every outbox claim, every continuation and every restart is executed by a
 * process that knows nothing about this test.
 *
 * <p>The readiness fact itself is committed by the production lease coordinator
 * ({@link RebuildLeaseCoordinator}); no callback endpoint and no fixture
 * outcome is used anywhere in this class.
 */
@Tag("database-integration")
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = {
        "spec.agent.action-eligibility.mode=enforced",
        "logging.file.name=build/c16-e2e/cross-process.log"
})
class C16CrossProcessWorkerAcceptanceTest {

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
    @Autowired RebuildLeaseCoordinator leases;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired PlatformTransactionManager transactions;

    private static final Path JAR = Path.of("build", "libs", "spec-agent-backend-0.0.1-SNAPSHOT.jar")
            .toAbsolutePath();
    private static final List<Process> WORKERS = new ArrayList<>();
    private static final List<UUID> TOUCHED = new ArrayList<>();

    @BeforeAll
    static void requireShippedArtifact() {
        assertThat(Files.isRegularFile(JAR)).as("the shipped application jar must exist: %s", JAR).isTrue();
    }

    @AfterAll
    static void stopEverything() throws Exception {
        stopWorkers();
    }

    /**
     * Stops every worker this test started.
     *
     * <p>Called before the fixture rows are deleted and again after the class
     * finishes. A worker that is still alive can write a continuation child run
     * while the parent row is being removed, which the self-referencing
     * foreign key on {@code agent_runs.parent_run_id} rejects.
     */
    static void stopWorkers() {
        for (Process worker : WORKERS) {
            try {
                worker.descendants().toList().forEach(ProcessHandle::destroyForcibly);
                worker.destroyForcibly();
                worker.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        WORKERS.clear();
    }

    @AfterEach
    void cleanProjects() {
        // The workers are still running while the fixture rows are deleted.
        // A live worker can commit a continuation child run (which references
        // its parent through agent_runs.parent_run_id) between clearing that
        // link and deleting the parent row, and the foreign key then rejects
        // the DELETE. Stopping the workers first removes the writer, so the
        // cleanup below is deterministic.
        stopWorkers();
        // Snapshot and clear BEFORE deleting anything. This class runs real
        // worker processes against a database-wide execution-mode row, so a
        // failed teardown must never leave that row in PER_TASK: every later
        // Spring context in this JVM then refuses to start and one defect
        // cascades into hundreds of unrelated failures. The mode is restored in
        // finally, whether or not any row cleanup succeeded.
        var pending = List.copyOf(TOUCHED);
        TOUCHED.clear();
        try {
            for (UUID project : pending) {
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
                // agent_runs references itself through parent_run_id. Clear the
                // link first: a single DELETE over parent and child rows in one
                // statement does not guarantee that the parent row is gone before
                // the child row is checked, and the foreign key then rejects it.
                jdbc.update("UPDATE agent_runs SET parent_run_id = NULL WHERE project_id = ?", project);
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
                // with no ON DELETE action (V49), so the generation cannot be removed
                // while a grant still points at it. The shared-Python indexing path
                // writes one grant per workload; it must be deleted first. The same
                // ordering is already proven in C16RetrievalAwaitGenerationEndToEndTest.
                jdbc.update("DELETE FROM retrieval_scope_grants WHERE corpus_id = ?", project);
                jdbc.update("DELETE FROM retrieval_index_generations WHERE corpus_id = ?", project);
                jdbc.update("DELETE FROM nodes WHERE project_id = ?", project);
                jdbc.update("UPDATE projects SET active_route_id = NULL WHERE id = ?", project);
                jdbc.update("DELETE FROM routes WHERE project_id = ?", project);
                jdbc.update("DELETE FROM projects WHERE id = ?", project);
            }
        } finally {
            // Unconditional: the database-wide admission row must never be left
            // in the task-level mode this class switched to.
            jdbc.update("UPDATE runtime_execution_mode SET mode='SINGLE_EXECUTOR',"
                    + " updated_by='c16 acceptance rollback' WHERE id=1");
        }
    }

    // ---------------------------------------------------------------- workers

    /** Starts one independent application process; it knows nothing about this test. */
    private Process startWorker(String name, int port) throws Exception {
        Path log = Path.of("build", "c16-e2e", "worker-" + name + "-" + port + ".log").toAbsolutePath();
        Files.createDirectories(log.getParent());
        var builder = new ProcessBuilder("java", "-jar", JAR.toString(),
                "--server.port=" + port,
                "--spec.agent.execution.multi-worker-enabled=true",
                "--spec.agent.brain.worker.enabled=true",
                "--spec.embedding-settings.worker-enabled=false",
                "--spec.agent.brain.engine=fake",
                "--spec.agent.brain.worker.poll-interval-ms=1000");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SPEC_AGENT_BRAIN_ENGINE", "fake");
        builder.environment().put("SPEC_AGENT_BRAIN_WORKER_ENABLED", "true");
        builder.environment().put("SPEC_AGENT_EMBEDDING_WORKER_ENABLED", "true");
        // The worker must talk to the SAME isolated database as this test.
        // Without this it falls back to the application default
        // (localhost:5434/spec_agent), which is a business database this
        // repository must never touch, and its execution-mode registration
        // then refuses the PER_TASK fleet.
        builder.environment().put("SPRING_DATASOURCE_URL", isolatedDatabaseUrl());
        builder.environment().put("SPRING_DATASOURCE_USERNAME", isolatedDatabaseUser());
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", isolatedDatabasePassword());
        builder.environment().put("SPRING_FLYWAY_URL", isolatedDatabaseUrl());
        builder.environment().put("SPRING_FLYWAY_USER", isolatedDatabaseUser());
        builder.environment().put("SPRING_FLYWAY_PASSWORD", isolatedDatabasePassword());
        // The worker must run the same profile as the test: the test profile
        // carries the deterministic master key and internal secret the test
        // database's ciphertext was written with. A worker on the default
        // profile has no key and refuses to start.
        builder.environment().put("SPRING_PROFILES_ACTIVE", "test");
        Process process = builder.start();
        WORKERS.add(process);
        awaitHealthy(process, port, log);
        return process;
    }

    /** The isolated database this test was pointed at, or a hard failure. */
    private static String isolatedDatabaseUrl() {
        String url = System.getenv("SPEC_AGENT_TEST_DB_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "SPEC_AGENT_TEST_DB_URL must be set: the cross-process workers must "
                            + "join the same isolated database as this test");
        }
        return url;
    }

    private static String isolatedDatabaseUser() {
        String user = System.getenv("SPEC_AGENT_TEST_DB_USERNAME");
        if (user == null || user.isBlank()) {
            throw new IllegalStateException("SPEC_AGENT_TEST_DB_USERNAME must be set");
        }
        return user;
    }

    private static String isolatedDatabasePassword() {
        String password = System.getenv("SPEC_AGENT_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("SPEC_AGENT_TEST_DB_PASSWORD must be set");
        }
        return password;
    }

    private void awaitHealthy(Process process, int port, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        try (HttpClient http = HttpClient.newHttpClient()) {
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("worker died during startup: " + Files.readString(log));
                }
                try {
                    HttpResponse<Void> response = http.send(HttpRequest
                            .newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                            .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) return;
                } catch (IOException unavailable) { }
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("worker never became healthy: " + Files.readString(log));
    }

    /** The documented maintenance-window mode switch, performed on this round's database only. */
    private void switchToPerTaskFleet() {
        jdbc.update("UPDATE runtime_execution_mode SET mode='PER_TASK', updated_by='c16 acceptance' WHERE id=1");
    }

    // ---------------------------------------------------------------- fixture

    record Fixture(ActorContext actor, UUID project, UUID rootNode, UUID route, String profileId) {}

    private Fixture fixture() {
        UUID org = UUID.randomUUID(), user = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org, "c16-cross-org", "STANDARD");
        identities.insertUserIfAbsent(user, "c16-cross-owner");
        identities.upsertMembership(org, user, OrgRole.OWNER);
        ActorContext actor = ActorContext.oidc(user, org);
        UUID project, route, root;
        String profileId;
        try (var scope = ActorContextHolder.bind(actor)) {
            var config = new EmbeddingSettings.Config("OPENAI_COMPATIBLE",
                    "http://127.0.0.1:18399/v1", "c16-cross-" + org, 30, 8, "raw-text.v1");
            settings.save(new EmbeddingSettings.Draft(config, "c16-cross-key", 0L));
            profileId = settings.register(config, 3, null, 1).profileId();
            Project created = projects.createProject("c16-cross-" + UUID.randomUUID());
            project = created.id();
            route = created.activeRouteId();
            Node node = nodes.createRootNode(project, route, "c16 cross process source", null,
                    List.of(), true);
            root = node.id();
        }
        TOUCHED.add(project);
        return new Fixture(actor, project, root, route, profileId);
    }

    private AgentRun createRun(Fixture f, String text) {
        try (var scope = ActorContextHolder.bind(f.actor())) {
            return runService.createQueuedRunWithInputResultForRoute(f.project(), "ANSWER_TIP",
                    f.rootNode(), null, text, null, null, null, null, null);
        }
    }

    /** Creates the generation, the pending binding and the waiting subscription. */
    record Prepared(Fixture f, UUID generation, AgentRun owner, AgentRun waiter,
                     CapabilityResult invocation) {}

    private Prepared prepare(Fixture f) {
        UUID generation;
        try (var scope = ActorContextHolder.bind(f.actor())) {
            rebuilds.start(f.project(), f.profileId());
            generation = jdbc.queryForObject("SELECT id FROM embedding_rebuilds WHERE corpus_id = ?",
                    UUID.class, f.project());
        }
        AgentRun owner = createRun(f, "c16 cross owner");
        var invoked = capabilityRuntime.invokeDeferredAllowed("run:" + owner.id() + ":proposal:c16",
                RetrievalAwaitGenerationCapability.ID, f.project(), owner.id(), Map.of());
        AgentRun waiter = createRun(f, "c16 cross waiter");
        try (var scope = ActorContextHolder.bind(f.actor())) {
            ContextSnapshot snapshot = contextBuilder.buildForRoute(f.project(), f.route(),
                    f.rootNode(), waiter.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(waiter.id(), snapshot.id(), "c16 cross context");
            AgentRequestEnvelope base = snapshotBuilder.buildEnvelope(waiter.id(), snapshot,
                    new AgentEvent("CONTINUE", f.rootNode(), null, null), new DecisionBudget(1));
            AgentRequestEnvelope v4 = gate.prepareDecisionRequest(base);
            assertThat(v4.pendingDependencies().dependencies())
                    .as("the waiting run must see the pending readiness dependency")
                    .isNotEmpty();
            ActionProposal proposal = new ActionProposal("WAIT",
                    Map.of("dependencyRef", invoked.invocationId().toString()),
                    v4.pendingDependencies().snapshotId(), v4.pendingDependencies().contextHash(),
                    List.of(), UUID.randomUUID(), waiter.id().toString(), List.of());
            new TransactionTemplate(transactions).executeWithoutResult(tx ->
                    waitDependencies.subscribe(v4, proposal));
            agentRunService.complete(waiter.id(), AgentRunStatus.COMPLETED, "c16 cross waiter parked");
            agentRunService.complete(owner.id(), AgentRunStatus.COMPLETED, "c16 cross owner parked");
        }
        return new Prepared(f, generation, owner, waiter, invoked);
    }

    /** Commits the readiness fact with the production lease coordinator. */
    private void commitReadiness(Prepared p) {
        var token = leases.claim(p.generation()).orElseThrow();
        leases.finish(token, 1, 1, true);
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_rebuilds WHERE id = ?",
                String.class, p.generation())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_rebuild_outcomes WHERE generation_id = ?",
                String.class, p.generation())).isEqualTo("READY");
    }

    private boolean awaitState(String sql, Map<String, Object> params, String expected, long seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            try {
                String value = named.queryForObject(sql, params, String.class);
                if (expected.equals(value)) return true;
            } catch (RuntimeException notYet) { }
            try { Thread.sleep(250); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void twoIndependentWorkersDeliverTheReadinessFactExactlyOnce() throws Exception {
        switchToPerTaskFleet();
        Fixture f = fixture();
        Process workerA = startWorker("A", 18091);
        Process workerB = startWorker("B", 18092);

        Prepared p = prepare(f);
        commitReadiness(p);

        // Both workers scan the same committed fact; exactly one CAS may win.
        assertThat(awaitState("SELECT status FROM capability_invocations WHERE id = :id",
                Map.of("id", p.invocation().invocationId()), "SUCCEEDED", 120))
                .as("an independent worker must deliver the committed readiness fact").isTrue();

        assertThat(jdbc.queryForObject("SELECT state FROM agent_run_wait_subscriptions WHERE wait_run_id = ?",
                String.class, p.waiter().id())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_continuation_checks WHERE run_id = ?",
                Integer.class, p.waiter().id()))
                .as("a duplicated delivery must not create a second outbox row").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM capability_invocations WHERE id = ?",
                Integer.class, p.invocation().invocationId())).isEqualTo(1);
        assertThat(workerA.isAlive()).isTrue();
        assertThat(workerB.isAlive()).isTrue();
    }

    @Test
    void aCommittedReadinessFactIsDeliveredAfterTheOwningProcessIsKilled() throws Exception {
        switchToPerTaskFleet();
        Fixture f = fixture();
        Process workerA = startWorker("A", 18093);

        Prepared p = prepare(f);
        commitReadiness(p);

        // Kill the process before any notification scan could run.
        workerA.destroyForcibly();
        workerA.waitFor(20, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT status FROM capability_invocations WHERE id = ?",
                String.class, p.invocation().invocationId()))
                .as("no scanner ran before the crash").isEqualTo("PENDING");

        // A fresh process must observe the committed fact and deliver it exactly once.
        startWorker("C", 18094);
        assertThat(awaitState("SELECT status FROM capability_invocations WHERE id = :id",
                Map.of("id", p.invocation().invocationId()), "SUCCEEDED", 120))
                .as("a restarted worker must deliver the committed readiness fact").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_continuation_checks WHERE run_id = ?",
                Integer.class, p.waiter().id())).isEqualTo(1);
    }

    @Test
    void theContinuationOutboxIsConsumedExactlyOnceAcrossARestart() throws Exception {
        switchToPerTaskFleet();
        Fixture f = fixture();
        Process workerA = startWorker("A", 18095);

        Prepared p = prepare(f);
        commitReadiness(p);
        assertThat(awaitState("SELECT status FROM capability_invocations WHERE id = :id",
                Map.of("id", p.invocation().invocationId()), "SUCCEEDED", 120)).isTrue();

        // Kill the worker right after the outbox row is committed.
        workerA.destroyForcibly();
        workerA.waitFor(20, TimeUnit.SECONDS);
        startWorker("D", 18096);
        assertThat(awaitState("SELECT count(*) FROM agent_run_continuation_checks"
                + " WHERE run_id = :run AND processed_at IS NOT NULL", Map.of("run", p.waiter().id()), "1", 120))
                .as("the committed outbox row must be consumed exactly once").isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_continuation_checks WHERE run_id = ?",
                Integer.class, p.waiter().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, p.waiter().id()))
                .as("a restart must not create a second continuation child").isLessThanOrEqualTo(1);
    }
}
