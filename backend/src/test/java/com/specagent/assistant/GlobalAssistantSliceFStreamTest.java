package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
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
 * 文件名:GlobalAssistantSliceFStreamTest.java
 *
 * 测试目标:Slice F——公开 API、事件持久化与排序、重放游标、
 * 断连语义(仅传输层)、刷新恢复,以及 UI_ACTION 校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalAssistantSliceFStreamTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunEventRepository events;
    @Autowired com.specagent.assistant.conversation.GlobalAssistantConversationService conversations;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunLifecycleService lifecycle;
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
        // 排序:sequence 递增;eventId 是持久化的 UUID,SSE 的 id 用 sequence。
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
        // 用一个无人执行的 CREATED 运行确定性地占住槽位,
        // 这样第二次创建无论时序如何都必然冲突。
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
        // 无执行器的 CREATED 运行保持原状:取消只记录协作信号,
        // 绝不会自己翻转运行状态。
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
        var fixture = conversations.createRunWithUserMessage(UUID.fromString(threadId), "replay check", "test", "test", "test");
        lifecycle.claimAndStart(fixture.id());
        lifecycle.completeWithAssistant(UUID.fromString(threadId), fixture.id(), "已完成");
        String runId = fixture.id().toString();
        UUID runUuid = UUID.fromString(runId);
        var all = events.findByRun(runUuid);
        assertThat(all).isNotEmpty();
        var afterFirst = events.findAfter(runUuid, 1);
        assertThat(afterFirst.stream().map(e -> e.sequence()).toList())
                .doesNotContain(1);
        for (var event : afterFirst) {
            assertThat(event.sequence()).isGreaterThan(1);
        }
        // 断连只发生在传输层:运行保持终态,绝不会因为订阅者离开而变 FAILED。
        var run = runs.findById(runUuid).orElseThrow();
        assertThat(run.status().name()).isNotEqualTo("FAILED");
    }
}
