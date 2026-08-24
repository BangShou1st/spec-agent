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
    void sameKeySameProjectSameRequestReturnsSameRun() throws Exception {
        Project project = projectService.createProject("Idem sequential " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(), true);
        String sharedKey = key("seq");

        UUID selectedOptionId = UUID.randomUUID();
        String first = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(),
                selectedOptionId, "same request");
        String second = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(),
                selectedOptionId, "same request");

        assertThat(second).isEqualTo(first);
        assertThat(runCount(sharedKey)).isEqualTo(1);
        assertThat(runCreatedEventCount(sharedKey)).isEqualTo(1);
    }

    @Test
    void sameKeyAcrossDifferentProjectsCreatesIndependentRuns() throws Exception {
        Project projectA = projectService.createProject("Idem project A " + UUID.randomUUID());
        Project projectB = projectService.createProject("Idem project B " + UUID.randomUUID());
        var rootA = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(),
                "Root A?", null, List.of(), true);
        var rootB = nodeService.createRootNode(projectB.id(), projectB.activeRouteId(),
                "Root B?", null, List.of(), true);
        String sharedKey = key("cross-project");

        String runA = createRunViaHttp(projectA.id(), sharedKey, "ANSWER_TIP", rootA.id(),
                null, "same key");
        String runB = createRunViaHttp(projectB.id(), sharedKey, "ANSWER_TIP", rootB.id(),
                null, "same key");

        assertThat(runB).isNotEqualTo(runA);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE project_id = ? AND idempotency_key = ?",
                Integer.class, projectA.id(), sharedKey)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE project_id = ? AND idempotency_key = ?",
                Integer.class, projectB.id(), sharedKey)).isEqualTo(1);
    }

    @Test
    void concurrentSameProjectSameRequestCreatesOneRun() throws Exception {
        Project project = projectService.createProject("Idem concurrent " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(),
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
                    return createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(),
                            null, "concurrent");
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
            assertThat(runCreatedEventCount(sharedKey)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idempotencyKeyReuseWithDifferentRequestReturns409() throws Exception {
        Project project = projectService.createProject("Idem operation mismatch " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(), true);
        String sharedKey = key("operation-mismatch");

        createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), null, "answer");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestPayload("GENERATE_ARTIFACT", null, null, null, sharedKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void sameKeySameProjectDifferentPayloadIsRejected() throws Exception {
        Project project = projectService.createProject("Idem payload mismatch " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(), true);
        String sharedKey = key("payload-mismatch");

        createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(),
                UUID.randomUUID(), "answer A");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestPayload("ANSWER_TIP", root.id(), UUID.randomUUID(),
                                "answer B", sharedKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void concurrentDifferentProjectsSameKeyCreateIndependentRuns() throws Exception {
        Project projectA = projectService.createProject("Idem concurrent A " + UUID.randomUUID());
        Project projectB = projectService.createProject("Idem concurrent B " + UUID.randomUUID());
        var rootA = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(),
                "Root A?", null, List.of(), true);
        var rootB = nodeService.createRootNode(projectB.id(), projectB.activeRouteId(),
                "Root B?", null, List.of(), true);
        String sharedKey = key("concurrent-cross-project");
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> futureA = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return createRunViaHttp(projectA.id(), sharedKey, "ANSWER_TIP", rootA.id(),
                        null, "same key");
            });
            Future<String> futureB = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return createRunViaHttp(projectB.id(), sharedKey, "ANSWER_TIP", rootB.id(),
                        null, "same key");
            });

            assertThat(futureA.get(30, TimeUnit.SECONDS))
                    .isNotEqualTo(futureB.get(30, TimeUnit.SECONDS));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agent_runs WHERE idempotency_key = ?",
                    Integer.class, sharedKey)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idempotencyIndexIsProjectScopedAndFingerprintColumnExists() {
        String indexDefinition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'agent_runs' "
                        + "AND indexname = 'idx_agent_runs_project_idempotency_key'",
                String.class);
        assertThat(indexDefinition).contains("(project_id, idempotency_key)");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'agent_runs' "
                        + "AND indexname = 'idx_agent_runs_idempotency_key'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'agent_runs' AND column_name = 'request_fingerprint'",
                Integer.class)).isEqualTo(1);
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
                "ANSWER_TIP", root.id(), null, "lost answer");
        String retriedRunId = createRunViaHttp(project.id(), sharedKey,
                "ANSWER_TIP", root.id(), null, "lost answer");

        assertThat(retriedRunId).isEqualTo(firstRunId);
        Integer answerRuns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE idempotency_key = ?",
                Integer.class, sharedKey);
        assertThat(answerRuns).isEqualTo(1);
    }

    private String createRun(UUID projectId, String idempotencyKey) throws Exception {
        return createRunViaHttp(projectId, idempotencyKey, "ANSWER_TIP", null, null,
                "answer text");
    }

    private String createRunViaHttp(UUID projectId, String idempotencyKey,
                                    UUID nodeId, String freeText) throws Exception {
        return createRunViaHttp(projectId, idempotencyKey, "ANSWER_TIP", nodeId, null, freeText);
    }

    private String createRunViaHttp(UUID projectId, String idempotencyKey,
                                    String operation, UUID nodeId,
                                    UUID selectedOptionId, String freeText) throws Exception {
        String payload = requestPayload(operation, nodeId, selectedOptionId, freeText, idempotencyKey);
        return performCreate(projectId, payload).runId();
    }

    private String requestPayload(String operation, UUID nodeId, UUID selectedOptionId,
                                  String freeText, String idempotencyKey) {
        return """
                {"operation": %s,
                 "nodeId": %s,
                 "selectedOptionId": %s,
                 "freeText": %s,
                 "idempotencyKey": %s}
                """.formatted(jsonString(operation), jsonUuid(nodeId), jsonUuid(selectedOptionId),
                jsonString(freeText), jsonString(idempotencyKey));
    }

    private String jsonUuid(UUID value) {
        return value == null ? "null" : jsonString(value.toString());
    }

    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
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

    private int runCreatedEventCount(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events e "
                        + "JOIN agent_runs r ON r.id = e.run_id "
                        + "WHERE r.idempotency_key = ? AND e.event_type = 'RUN_CREATED'",
                Integer.class, key);
        return count == null ? 0 : count;
    }
}
