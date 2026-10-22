package com.specagent.retrieval.workload;

import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.retrieval.config.EmbeddingRebuilds;
import com.specagent.retrieval.config.EmbeddingSettings;
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

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database acceptance for the rebuild lease.
 *
 * <p>Two properties must hold: a worker that is alive but slow keeps its lease
 * (the work is not done twice), and a rebuild whose lease keeps expiring
 * converges to an observable failure instead of being re-queued forever.
 */
@Tag("database-integration")
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = {
        "spec.embedding-settings.rebuild-lease-heartbeat-ms=1000"
})
class RebuildLeaseAcceptanceTest {

    @Autowired IdentityRepository identities;
    @Autowired EmbeddingSettings settings;
    @Autowired EmbeddingRebuilds rebuilds;
    @Autowired ProjectService projects;
    @Autowired NodeService nodes;
    @Autowired RebuildLeaseCoordinator leases;
    @Autowired JdbcTemplate jdbc;

    private final java.util.List<UUID> touchedProjects = new java.util.ArrayList<>();

    record Fixture(ActorContext actor, UUID project, UUID generation) {}

    private Fixture fixture() {
        UUID org = UUID.randomUUID(), user = UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org, "rebuild-lease-org", "STANDARD");
        identities.insertUserIfAbsent(user, "rebuild-lease-owner");
        identities.upsertMembership(org, user, OrgRole.OWNER);
        ActorContext actor = ActorContext.oidc(user, org);
        UUID project, generation;
        String profileId;
        try (var scope = ActorContextHolder.bind(actor)) {
            var config = new EmbeddingSettings.Config("OPENAI_COMPATIBLE",
                    "http://127.0.0.1:18399/v1", "rebuild-lease-" + org, 30, 8, "raw-text.v1");
            settings.save(new EmbeddingSettings.Draft(config, "rebuild-lease-key", 0L));
            profileId = settings.register(config, 3, null, 1).profileId();
            Project created = projects.createProject("rebuild-lease-" + UUID.randomUUID());
            project = created.id();
            Node node = nodes.createRootNode(project, created.activeRouteId(),
                    "rebuild lease source", null, List.of(), true);
            rebuilds.start(project, profileId);
            generation = jdbc.queryForObject("SELECT id FROM embedding_rebuilds WHERE corpus_id = ?",
                    UUID.class, project);
        }
        touchedProjects.add(project);
        return new Fixture(actor, project, generation);
    }

    @AfterEach
    void cleanUp() {
        for (UUID project : touchedProjects) {
            jdbc.update("DELETE FROM embedding_rebuild_outcomes WHERE project_id = ?", project);
            jdbc.update("DELETE FROM embedding_rebuilds WHERE corpus_id = ?", project);
            jdbc.update("DELETE FROM retrieval_index_jobs WHERE corpus_id = ?", project);
            jdbc.update("DELETE FROM retrieval_index_heads WHERE corpus_id = ?", project);
            jdbc.update("DELETE FROM retrieval_index_generations WHERE corpus_id = ?", project);
            jdbc.update("DELETE FROM retrieval_entries WHERE project_id = ?", project);
            jdbc.update("DELETE FROM nodes WHERE project_id = ?", project);
            jdbc.update("UPDATE projects SET active_route_id = NULL WHERE id = ?", project);
            jdbc.update("DELETE FROM routes WHERE project_id = ?", project);
            jdbc.update("DELETE FROM projects WHERE id = ?", project);
        }
        touchedProjects.clear();
    }

    @Test
    void anAliveWorkerKeepsItsLeaseWhileItIsStillWorking() throws Exception {
        Fixture f = fixture();
        var token = leases.claim(f.generation()).orElseThrow();
        long before = jdbc.queryForObject(
                "SELECT extract(epoch from lease_until)::bigint FROM embedding_rebuilds WHERE id = ?",
                Long.class, f.generation());

        // The heartbeat of the live worker must extend the lease it still holds.
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        long after = before;
        while (System.nanoTime() < deadline && after <= before) {
            Thread.sleep(250);
            after = jdbc.queryForObject(
                    "SELECT extract(epoch from lease_until)::bigint FROM embedding_rebuilds WHERE id = ?",
                    Long.class, f.generation());
        }
        assertThat(after).as("a live worker must renew the lease it still holds").isGreaterThan(before);
        assertThat(jdbc.queryForObject("SELECT execution_epoch FROM embedding_rebuilds WHERE id = ?",
                Long.class, f.generation()))
                .as("the renewed lease must not change the fencing epoch").isEqualTo(token.epoch());
        leases.fail(token, "TEST_FINISHED");
    }

    @Test
    void aRebuildThatKeepsLosingItsLeaseConvergesToAnObservableFailure() {
        Fixture f = fixture();
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            var token = leases.claim(f.generation()).orElseThrow();
            assertThat(token.epoch()).isEqualTo(attempt);
            // Simulate a worker that died: the lease expires without finishing.
            jdbc.update("UPDATE embedding_rebuilds SET lease_until = clock_timestamp() - interval '1 second'"
                    + " WHERE id = ?", f.generation());
            leases.recoverExpired();
        }
        {
            String state = jdbc.queryForObject("SELECT state FROM embedding_rebuilds WHERE id = ?",
                    String.class, f.generation());
            String error = jdbc.queryForObject("SELECT error_code FROM embedding_rebuilds WHERE id = ?",
                    String.class, f.generation());
            assertThat(state).as("the rebuild must converge instead of being re-queued forever")
                    .isEqualTo("FAILED");
            assertThat(error).isEqualTo("REBUILD_LEASE_EXPIRED");
        }
    }
}
