package com.specagent.agent.policy;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-backed idempotency of proposal creation: concurrent creations
 * with the same idempotency key must converge on one persisted row, return
 * the same proposal to every caller, and never leak a unique-constraint
 * failure — the database is the final arbiter, not a check-then-insert.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentProposalIdempotencyConcurrencyIntegrationTest {

    private static final String KEY_PREFIX = "idem-conc-";

    @Autowired private AgentProposalService proposalService;
    @Autowired private ProjectService projectService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;

    @BeforeEach
    void setUp() {
        project = projectService.createProject(
                "提案幂等并发测试-" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM agent_proposals WHERE idempotency_key LIKE ?",
                KEY_PREFIX + "%");
    }

    @Test
    void concurrentCreateProposalWithSameKeyConvergesOnOneRow() throws Exception {
        int racers = 6;
        String sharedKey = KEY_PREFIX + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CyclicBarrier startLine = new CyclicBarrier(racers);
        try {
            List<Future<AgentProposal>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit((Callable<AgentProposal>) () -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    ActionProposal proposal = new ActionProposal(
                            "CREATE_NODE",
                            Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                                    "content", Map.of("text", "concurrent")),
                            UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                            List.of(), UUID.randomUUID(), sharedKey,
                            List.of());
                    return proposalService.createProposal(proposal,
                            UUID.randomUUID(), project.id(),
                            project.activeRouteId());
                }));
            }

            List<UUID> returnedIds = new ArrayList<>();
            for (Future<AgentProposal> future : futures) {
                // A unique-violation leaking here would be the exact race the
                // atomic insert must close.
                returnedIds.add(future.get(30, TimeUnit.SECONDS).id());
            }

            Integer rowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agent_proposals WHERE idempotency_key = ?",
                    Integer.class, sharedKey);
            assertThat(rowCount).as("exactly one persisted proposal").isEqualTo(1);

            assertThat(returnedIds).hasSize(racers);
            assertThat(returnedIds.stream().distinct().count())
                    .as("every caller observes the same persisted proposal")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sequentialRepeatCreationReturnsExistingProposalWithoutSecondRow() {
        String key = KEY_PREFIX + UUID.randomUUID();
        ActionProposal template = new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "repeat")),
                UUID.randomUUID(), "hash", List.of(), UUID.randomUUID(), key,
                List.of());

        AgentProposal first = proposalService.createProposal(
                template, UUID.randomUUID(), project.id(), project.activeRouteId());
        AgentProposal second = proposalService.createProposal(
                template, UUID.randomUUID(), project.id(), project.activeRouteId());

        assertThat(second.id()).isEqualTo(first.id());
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_proposals WHERE idempotency_key = ?",
                Integer.class, key);
        assertThat(rowCount).isEqualTo(1);
    }
}
