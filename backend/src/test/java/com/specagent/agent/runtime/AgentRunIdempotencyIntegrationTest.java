package com.specagent.agent.runtime;

import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2 create-run idempotency over real PostgreSQL: the same clientRequestId
 * resolves to exactly one persisted run — sequentially, concurrently, and
 * across a lost-response retry. The unique index arbitrates; no unique
 * exception may leak to callers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentRunIdempotencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String key(String label) {
        return "idem-" + label + "-" + UUID.randomUUID();
    }

    @org.junit.jupiter.api.AfterEach
    void removeIdempotencyFixtures() {
        // Non-transactional test class: delete committed rows so leftover
        // queued runs never poison other tests that claim the oldest run.
        jdbcTemplate.update(
                "DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE idempotency_key LIKE ?)",
                "idem-%");
        jdbcTemplate.update("DELETE FROM agent_runs WHERE idempotency_key LIKE ?", "idem-%");
    }

    @Test
    void sameIdempotencyKeyReturnsSameRun() throws Exception {
        Project project = projectService.createProject("Idem sequential " + UUID.randomUUID());
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(), true);
        String sharedKey = key("seq");

        String first = createRun(project.id(), sharedKey);
        String second = createRun(project.id(), sharedKey);

        assertThat(second).isEqualTo(first);
        assertThat(runCount(sharedKey)).isEqualTo(1);
    }

    @Test
    void concurrentCreateWithSameKeyCreatesOneRun() throws Exception {
        Project project = projectService.createProject("Idem concurrent " + UUID.randomUUID());
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(), true);
        String sharedKey = key("conc");

        int racers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CyclicBarrier startLine = new CyclicBarrier(racers);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit((Callable<String>) () -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return createRunViaHttp(project.id(), sharedKey, null, "concurrent");
                }));
            }
            List<String> runIds = new ArrayList<>();
            for (Future<String> future : futures) {
                runIds.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(runIds.stream().distinct().count())
                    .as("all concurrent creators see the same run")
                    .isEqualTo(1);
            assertThat(runCount(sharedKey)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void responseLossRetryDoesNotCreateSecondAnswerRun() throws Exception {
        Project project = projectService.createProject("Idem replay " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(), true);
        String sharedKey = key("replay");

        // First request lands server-side; the response never reaches the
        // client. The client retries with the SAME key.
        String firstRunId = createRunViaHttp(project.id(), sharedKey,
                root.id(), "lost answer");
        String retriedRunId = createRunViaHttp(project.id(), sharedKey,
                root.id(), "retry answer");

        assertThat(retriedRunId).isEqualTo(firstRunId);
        Integer answerRuns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE idempotency_key = ?",
                Integer.class, sharedKey);
        assertThat(answerRuns).isEqualTo(1);
    }

    private String createRun(UUID projectId, String idempotencyKey) throws Exception {
        return createRunViaHttp(projectId, idempotencyKey, null, "answer text");
    }

    private String createRunViaHttp(UUID projectId, String idempotencyKey,
                                    UUID nodeId, String freeText) throws Exception {
        String payload = """
                {"operation": "ANSWER_TIP",
                 "nodeId": %s,
                 "freeText": "%s",
                 "idempotencyKey": "%s"}
                """.formatted(nodeId == null ? "null" : '"' + nodeId.toString() + '"',
                freeText, idempotencyKey);
        return performCreate(projectId, payload).runId();
    }

    private record MvcResultLike(String runId) {
    }

    private MvcResultLike performCreate(UUID projectId, String payload) throws Exception {
        // The create endpoint must never leak a unique-violation: concurrent
        // same-key requests all observe 202 with the SAME runId.
        var mvcResult = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", projectId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").exists())
                .andReturn();
        String body = mvcResult.getResponse().getContentAsString();
        String runId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("runId").asText();
        return new MvcResultLike(runId);
    }

    private int runCount(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE idempotency_key = ?",
                Integer.class, key);
        return count == null ? 0 : count;
    }
}
