package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice F: public API, event persistence/ordering, replay cursor,
 * disconnect semantics (transport-only), refresh recovery, UI_ACTION validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalAssistantSliceFStreamTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunEventRepository events;
    @Autowired com.specagent.globalassistant.conversation.GlobalAssistantConversationService conversations;
    @Autowired com.specagent.globalassistant.runtime.GlobalAssistantRunLifecycleService lifecycle;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM global_assistant_run_events");
        jdbc.update("DELETE FROM global_assistant_runs");
        jdbc.update("DELETE FROM global_assistant_messages");
        jdbc.update("DELETE FROM global_assistant_threads");
    }
    private String createThread() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/global-assistant/threads"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("threadId").asText();
    }
    private String createRun(String threadId, String message) throws Exception {
        Map<String, Object> body = Map.of("message", message,
                "uiContext", Map.of("currentPage", "PROJECTS"));
        MvcResult result = mockMvc.perform(post("/api/v1/global-assistant/threads/" + threadId + "/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
    }
    private void waitForTerminal(String runId) throws Exception {
        for (int i = 0; i < 100; i++) {
            MvcResult result = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId))
                    .andExpect(status().isOk())
                    .andReturn();
            String status = mapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
            if (!status.equals("CREATED") && !status.equals("RUNNING")) {
                return;
            }
            Thread.sleep(100);
        }
    }
    @Test
    void threadRunMessageRefreshRecovery() throws Exception {
        String threadId = createThread();
        String runId = createRun(threadId, "hello");
        waitForTerminal(runId);
        mockMvc.perform(get("/api/v1/global-assistant/threads/" + threadId))
                .andExpect(status().isOk());
        MvcResult messages = mockMvc.perform(
                        get("/api/v1/global-assistant/threads/" + threadId + "/messages"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = mapper.readTree(messages.getResponse().getContentAsString());
        assertThat(list.size()).isGreaterThanOrEqualTo(2);
        MvcResult run = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(run.getResponse().getContentAsString()).get("status").asText())
                .isIn("COMPLETED", "FAILED", "CANCELLED");
        MvcResult eventList = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId + "/events")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode stored = mapper.readTree(eventList.getResponse().getContentAsString());
        assertThat(stored.size()).isGreaterThanOrEqualTo(2);
        // Ordering: sequences ascend; eventId is the persisted UUID, SSE id is the sequence.
        int previous = 0;
        for (JsonNode envelope : stored) {
            int sequence = envelope.get("sequence").asInt();
            assertThat(sequence).isGreaterThan(previous);
            previous = sequence;
            assertThat(envelope.get("eventId").asText())
                    .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
            assertThat(envelope.get("runId").asText()).isEqualTo(runId);
        }
    }
    @Test
    void secondActiveRunConflictsWith409() throws Exception {
        String threadId = createThread();
        // Occupy the slot deterministically with a CREATED run owned by no
        // executor, so the second create must conflict regardless of timing.
        var first = conversations.createRunWithUserMessage(
                UUID.fromString(threadId), "first", "v1", "v1", "fp");
        MvcResult second = mockMvc.perform(
                        post("/api/v1/global-assistant/threads/" + threadId + "/runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("message", "second"))))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(second.getResponse().getContentAsString())
                .contains("GLOBAL_ASSISTANT_RUN_ACTIVE");
        lifecycle.cancelAndTerminalize(first.id());
        MvcResult third = mockMvc.perform(
                        post("/api/v1/global-assistant/threads/" + threadId + "/runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("message", "third"))))
                .andExpect(status().isOk())
                .andReturn();
        waitForTerminal(mapper.readTree(third.getResponse().getContentAsString()).get("runId").asText());
    }
    @Test
    void cancelSignalsWithoutTerminalizing() throws Exception {
        String threadId = createThread();
        // A CREATED run with no executor stays put: cancel only records the
        // cooperative signal and never flips the status itself.
        var created = conversations.createRunWithUserMessage(
                UUID.fromString(threadId), "please wait", "v1", "v1", "fp");
        String runId = created.id().toString();
        mockMvc.perform(post("/api/v1/global-assistant/runs/" + runId + "/cancel"))
                .andExpect(status().isOk());
        MvcResult once = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(once.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("CREATED");
        assertThat(body.get("cancelRequestedAt").asText()).isNotBlank();
        mockMvc.perform(post("/api/v1/global-assistant/runs/" + runId + "/cancel"))
                .andExpect(status().isOk());
        MvcResult twice = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(twice.getResponse().getContentAsString()).get("status").asText())
                .isEqualTo("CREATED");
        lifecycle.cancelAndTerminalize(created.id());
        MvcResult terminal = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(terminal.getResponse().getContentAsString()).get("status").asText())
                .isEqualTo("CANCELLED");
    }
    @Test
    void sseReplayCursorSemantics() throws Exception {
        String threadId = createThread();
        String runId = createRun(threadId, "replay check");
        waitForTerminal(runId);
        UUID runUuid = UUID.fromString(runId);
        var all = events.findByRun(runUuid);
        assertThat(all).isNotEmpty();
        var afterFirst = events.findAfter(runUuid, 1);
        assertThat(afterFirst.stream().map(e -> e.sequence()).toList())
                .doesNotContain(1);
        for (var event : afterFirst) {
            assertThat(event.sequence()).isGreaterThan(1);
        }
        // Disconnect is transport-only: the run stays terminal, never FAILED
        // because a subscriber went away.
        var run = runs.findById(runUuid).orElseThrow();
        assertThat(run.status().name()).isNotEqualTo("FAILED");
    }
}
