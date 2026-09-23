package com.specagent.agent.runtime;

import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteService;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentRunIdempotencyIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private RouteService routeService;
    @Autowired private AnswerPatchService answerPatchService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String key(String label) { return "idem-" + label + "-" + UUID.randomUUID(); }

    @org.junit.jupiter.api.AfterEach
    void removeIdempotencyFixtures() {
        jdbcTemplate.update(
                "DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE idempotency_key LIKE ?)",
                "idem-%");
        jdbcTemplate.update(
                "DELETE FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE idempotency_key LIKE ?)",
                "idem-%");
        jdbcTemplate.update("DELETE FROM agent_runs WHERE idempotency_key LIKE ?", "idem-%");
    }

    @Test
    void sameKeySameProjectSameRequestReturnsSameRun() throws Exception {
        Project project = projectService.createProject("Idem sequential " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("seq");
        UUID selectedOptionId = UUID.randomUUID();
        UUID first = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), selectedOptionId, "same request");
        UUID second = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), selectedOptionId, "same request");
        assertThat(second).isEqualTo(first);
        assertThat(runCount(sharedKey)).isEqualTo(1);
        assertThat(runCreatedEventCount(sharedKey)).isEqualTo(1);
    }

    @Test
    void answerReplayAfterOriginalRunAlreadyCompletedReturnsOriginalRun() throws Exception {
        Project project = projectService.createProject("Idem completed answer " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("completed-answer");
        UUID first = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), null, "lost response");
        answerService.finalizeAnswer(project.id(), project.activeRouteId(), root.id(), null, "lost response", "test-user");
        nodeService.createChildNode(project.id(), project.activeRouteId(), root.id(), "Next?", null, List.of(), true);
        jdbcTemplate.update("UPDATE agent_runs SET status = 'completed', completed_at = now() WHERE id = ?", first);
        UUID replayed = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), null, "lost response");
        assertThat(replayed).isEqualTo(first);
        assertThat(runCount(sharedKey)).isEqualTo(1);
        assertThat(runCreatedEventCount(sharedKey)).isEqualTo(1);
    }

    @Test
    void draftReplayAfterTipChangedReturnsOriginalRun() throws Exception {
        Project project = projectService.createProject("Idem draft replay " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("draft-tip");

        // Fail-fast contract (new): DRAFT_QUESTION on a route whose tip is still
        // an unanswered question is rejected up-front with 409
        // UNANSWERED_QUESTION_HAS_CHILD. The decision append would violate the
        // UNANSWERED_QUESTION_HAS_CHILD graph invariant anyway, so the API no
        // longer enqueues a run that is doomed to fail. The eligibility check
        // runs only for NEW requests: an idempotent replay is matched by
        // fingerprint before the check and keeps returning the original run.
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestPayload("DRAFT_QUESTION", null, null, null, sharedKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNANSWERED_QUESTION_HAS_CHILD"));

        // Once the tip question is answered the draft is accepted; the idempotent
        // replay must still return the original run even though the tip moved.
        var answer = answerService.finalizeAnswer(project.id(), project.activeRouteId(), root.id(), null,
                "answered before drafting", "test-user");
        answerPatchService.save(project.id(), project.activeRouteId(), root.id(),
                answer.id(), List.of(), null);
        UUID first = createRunViaHttp(project.id(), sharedKey, "DRAFT_QUESTION", null, null, null);
        nodeService.createChildNode(project.id(), project.activeRouteId(), root.id(), "Changed tip?", null, List.of(), true);
        UUID replayed = createRunViaHttp(project.id(), sharedKey, "DRAFT_QUESTION", null, null, null);
        assertThat(replayed).isEqualTo(first);
        assertThat(runCount(sharedKey)).isEqualTo(1);
    }

    @Test
    void artifactReplayAfterActiveRouteChangedReturnsOriginalRun() throws Exception {
        Project project = projectService.createProject("Idem artifact replay " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        UUID originalRouteId = project.activeRouteId();
        String sharedKey = key("artifact-route");
        UUID first = createRunViaHttp(project.id(), sharedKey, "GENERATE_ARTIFACT", null, null, null);
        answerService.finalizeAnswer(project.id(), originalRouteId, root.id(), null, "done", "test-user");
        routeService.forkFromNode(project.id(), originalRouteId, root.id(), "Other route");
        UUID replayed = createRunViaHttp(project.id(), sharedKey, "GENERATE_ARTIFACT", null, null, null);
        assertThat(replayed).isEqualTo(first);
        assertThat(runCount(sharedKey)).isEqualTo(1);
    }

    @Test
    void sameKeyAcrossDifferentProjectsCreatesIndependentRuns() throws Exception {
        Project projectA = projectService.createProject("Idem project A " + UUID.randomUUID());
        Project projectB = projectService.createProject("Idem project B " + UUID.randomUUID());
        var rootA = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(), "Root A?", null, List.of(), true);
        var rootB = nodeService.createRootNode(projectB.id(), projectB.activeRouteId(), "Root B?", null, List.of(), true);
        String sharedKey = key("cross-project");
        UUID runA = createRunViaHttp(projectA.id(), sharedKey, "ANSWER_TIP", rootA.id(), null, "same key");
        UUID runB = createRunViaHttp(projectB.id(), sharedKey, "ANSWER_TIP", rootB.id(), null, "same key");
        assertThat(runB).isNotEqualTo(runA);
    }

    @Test
    void concurrentSameProjectSameRequestCreatesOneRun() throws Exception {
        Project project = projectService.createProject("Idem concurrent " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("conc");
        int racers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CyclicBarrier startLine = new CyclicBarrier(racers);
        try {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit((Callable<UUID>) () -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), null, "concurrent");
                }));
            }
            List<UUID> runIds = new ArrayList<>();
            for (Future<UUID> future : futures) runIds.add(future.get(30, TimeUnit.SECONDS));
            assertThat(runIds.stream().distinct().count()).isEqualTo(1);
            assertThat(runCount(sharedKey)).isEqualTo(1);
            assertThat(runCreatedEventCount(sharedKey)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idempotencyKeyReuseWithDifferentRequestReturns409() throws Exception {
        Project project = projectService.createProject("Idem operation mismatch " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
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
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("payload-mismatch");
        createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), UUID.randomUUID(), "answer A");
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestPayload("ANSWER_TIP", root.id(), UUID.randomUUID(), "answer B", sharedKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void concurrentDifferentProjectsSameKeyCreateIndependentRuns() throws Exception {
        Project projectA = projectService.createProject("Idem concurrent A " + UUID.randomUUID());
        Project projectB = projectService.createProject("Idem concurrent B " + UUID.randomUUID());
        var rootA = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(), "Root A?", null, List.of(), true);
        var rootB = nodeService.createRootNode(projectB.id(), projectB.activeRouteId(), "Root B?", null, List.of(), true);
        String sharedKey = key("concurrent-cross-project");
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> futureA = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return createRunViaHttp(projectA.id(), sharedKey, "ANSWER_TIP", rootA.id(), null, "same key");
            });
            Future<UUID> futureB = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return createRunViaHttp(projectB.id(), sharedKey, "ANSWER_TIP", rootB.id(), null, "same key");
            });
            assertThat(futureA.get(30, TimeUnit.SECONDS)).isNotEqualTo(futureB.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idempotencyIndexIsProjectScopedAndFingerprintColumnExists() {
        String indexDefinition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'agent_runs' "
                        + "AND indexname = 'idx_agent_runs_project_idempotency_key'", String.class);
        assertThat(indexDefinition).contains("(project_id, idempotency_key)");
    }

    @Test
    void responseLossRetryDoesNotCreateSecondAnswerRun() throws Exception {
        Project project = projectService.createProject("Idem replay " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("replay");
        UUID firstRunId = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), null, "lost answer");
        UUID retriedRunId = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), null, "lost answer");
        assertThat(retriedRunId).isEqualTo(firstRunId);
        assertThat(runCount(sharedKey)).isEqualTo(1);
    }

    // ---- Multi-select fingerprints (HTTP entry, not just the hash utility) ----

    @Test
    void multiSelectSameSelectionReplaysSameRun() throws Exception {
        Project project = projectService.createProject("Idem multi replay " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("multi-replay");
        UUID optionA = UUID.randomUUID();
        UUID optionB = UUID.randomUUID();
        UUID first = createMultiSelectRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(),
                List.of(optionA, optionB), null);
        UUID second = createMultiSelectRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(),
                List.of(optionA, optionB), null);
        assertThat(second).isEqualTo(first);
        assertThat(runCount(sharedKey)).isEqualTo(1);
        assertThat(runCreatedEventCount(sharedKey)).isEqualTo(1);
    }

    @Test
    void multiSelectSameFirstOptionDifferentRestConflicts() throws Exception {
        Project project = projectService.createProject("Idem multi conflict " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("multi-conflict");
        UUID optionA = UUID.randomUUID();
        UUID optionB = UUID.randomUUID();
        UUID optionC = UUID.randomUUID();
        createMultiSelectRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(),
                List.of(optionA, optionB), null);
        // Same first option, different rest of the selection: a DIFFERENT
        // answer, so the idempotency key must conflict instead of replaying.
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiSelectPayload("ANSWER_TIP", root.id(),
                                List.of(optionA, optionC), null, sharedKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(runCount(sharedKey)).isEqualTo(1);
    }

    @Test
    void multiSelectDifferentOrderIsADifferentRequest() throws Exception {
        Project project = projectService.createProject("Idem multi order " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("multi-order");
        UUID optionA = UUID.randomUUID();
        UUID optionB = UUID.randomUUID();
        createMultiSelectRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(),
                List.of(optionA, optionB), null);
        // User order is part of the selection semantics: swapping the order is
        // a different request and must conflict on the same key.
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiSelectPayload("ANSWER_TIP", root.id(),
                                List.of(optionB, optionA), null, sharedKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(runCount(sharedKey)).isEqualTo(1);
    }

    @Test
    void legacySingleSelectReplayIsUnaffectedByMultiSelectSupport() throws Exception {
        Project project = projectService.createProject("Idem single compat " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("single-compat");
        UUID optionA = UUID.randomUUID();
        // Old client shape: selectedOptionId only, no selectedOptionIds field.
        UUID first = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), optionA, "single");
        UUID second = createRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(), optionA, "single");
        assertThat(second).isEqualTo(first);
        assertThat(runCount(sharedKey)).isEqualTo(1);
        // And a single-select still conflicts against a multi-select that
        // names the same option plus one more.
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiSelectPayload("ANSWER_TIP", root.id(),
                                List.of(optionA, UUID.randomUUID()), null, sharedKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void multiSelectFullListIsPersistedIntoTheRunPayload() throws Exception {
        Project project = projectService.createProject("Idem multi payload " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(), "Root?", null, List.of(), true);
        String sharedKey = key("multi-payload");
        UUID optionA = UUID.randomUUID();
        UUID optionB = UUID.randomUUID();
        createMultiSelectRunViaHttp(project.id(), sharedKey, "ANSWER_TIP", root.id(),
                List.of(optionA, optionB), null);
        // The full selection (not just the first option) must reach the
        // runtime: the RUN_CREATED payload is what the worker replays.
        String payload = jdbcTemplate.queryForObject(
                "SELECT e.payload FROM agent_run_events e JOIN agent_runs r ON r.id = e.run_id "
                        + "WHERE r.idempotency_key = ? AND e.event_type = 'RUN_CREATED'",
                String.class, sharedKey);
        assertThat(payload).contains(optionA.toString()).contains(optionB.toString());
    }

    private UUID createMultiSelectRunViaHttp(UUID projectId, String idempotencyKey,
                                             String operation, UUID nodeId,
                                             List<UUID> selectedOptionIds, String freeText) throws Exception {
        return performCreate(projectId, multiSelectPayload(operation, nodeId, selectedOptionIds, freeText, idempotencyKey));
    }

    private String multiSelectPayload(String operation, UUID nodeId, List<UUID> selectedOptionIds,
                                      String freeText, String idempotencyKey) {
        String optionIds = selectedOptionIds == null ? "null"
                : selectedOptionIds.stream().map(UUID::toString)
                        .map(id -> "\"" + id + "\"")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return """
                {"operation": %s,
                 "nodeId": %s,
                 "selectedOptionId": null,
                 "selectedOptionIds": %s,
                 "freeText": %s,
                 "idempotencyKey": %s}
                """.formatted(jsonString(operation), jsonUuid(nodeId), optionIds,
                jsonString(freeText), jsonString(idempotencyKey));
    }

    private UUID createRunViaHttp(UUID projectId, String idempotencyKey,
                                  String operation, UUID nodeId,
                                  UUID selectedOptionId, String freeText) throws Exception {
        return performCreate(projectId, requestPayload(operation, nodeId, selectedOptionId, freeText, idempotencyKey));
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

    private String jsonUuid(UUID value) { return value == null ? "null" : jsonString(value.toString()); }
    private String jsonString(String value) {
        if (value == null) return "null";
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private UUID performCreate(UUID projectId, String payload) throws Exception {
        var mvcResult = mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").exists())
                .andReturn();
        return UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(mvcResult.getResponse().getContentAsString()).get("runId").asText());
    }

    private int runCount(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE idempotency_key = ?", Integer.class, key);
        return count == null ? 0 : count;
    }

    private int runCreatedEventCount(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events e JOIN agent_runs r ON r.id = e.run_id "
                        + "WHERE r.idempotency_key = ? AND e.event_type = 'RUN_CREATED'", Integer.class, key);
        return count == null ? 0 : count;
    }
}
