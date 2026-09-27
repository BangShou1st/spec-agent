package com.specagent.agent.policy;

import com.specagent.agent.protocol.ActionProposal;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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
 * 文件名:AgentProposalIdempotencyConcurrencyIntegrationTest.java
 *
 * 测试目标:验证提案创建的数据库级幂等性——携带相同幂等键的并发创建必须收敛到同一行
 * 持久化记录,所有调用方拿到同一个提案,且绝不泄漏唯一约束冲突;最终仲裁者是数据库本身,
 * 而不是"先查后插"。另验证顺序重复创建返回已存在提案且不产生第二行。
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
            // 唯一约束冲突如果在这里泄漏,正是原子插入要堵住的那个竞态。
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
