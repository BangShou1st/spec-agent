package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.stream.GlobalAssistantRunEventService;
import com.specagent.globalassistant.stream.GlobalAssistantStreamService;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FIX T/U/V/W/X RED: after-commit publish, UUID event ids, ordered handoff,
 * strict cursor handling through the real SSE endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalAssistantSseHardeningTest {
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantRunEventService runEvents;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @SpyBean GlobalAssistantStreamService streams;
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM global_assistant_run_events");
        jdbc.update("DELETE FROM global_assistant_runs");
        jdbc.update("DELETE FROM global_assistant_messages");
        jdbc.update("DELETE FROM global_assistant_threads");
    }
    private String createThread() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/global-assistant/threads"))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("threadId").asText();
    }
    private String createRun(String threadId, String message) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/global-assistant/threads/" + threadId + "/runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("message", message))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
    }
    private void waitForTerminal(String runId) throws Exception {
        for (int i = 0; i < 100; i++) {
            MvcResult result = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId))
                    .andExpect(status().isOk()).andReturn();
            String runStatus =
                    mapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
            if (!runStatus.equals("CREATED") && !runStatus.equals("RUNNING")) {
                return;
            }
            Thread.sleep(100);
        }
    }
    @Test
    void rolledBackEventNeverReachesSubscribers() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(
                thread.id(), "ghost check", "v1", "v1", "fp");
        new TransactionTemplate(txm).execute(txStatus -> {
            runEvents.append(run.id(), "STATUS", Map.of("message", "ghost"));
            txStatus.setRollbackOnly();
            return null;
        });
        verify(streams, never()).publish(eq(run.id()), any());
        assertThat(runEvents.findByRun(run.id())).isEmpty();
    }
    @Test
    void committedEventPublishesOnce() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(
                thread.id(), "commit check", "v1", "v1", "fp");
        runEvents.append(run.id(), "STATUS", Map.of("message", "hello"));
        verify(streams).publish(eq(run.id()), any());
    }
    @Test
    void sseEnvelopeUsesUuidEventIdAndSequenceSseId() throws Exception {
        String threadId = createThread();
        String runId = createRun(threadId, "stream check");
        waitForTerminal(runId);
        MvcResult started = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId + "/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        MvcResult done = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk()).andReturn();
        String body = done.getResponse().getContentAsString();
        assertThat(body).contains("id:1");
        assertThat(body).contains("RUN_COMPLETED");
        MvcResult listed = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId + "/events")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        JsonNode envelopes = mapper.readTree(listed.getResponse().getContentAsString());
        assertThat(envelopes.size()).isGreaterThanOrEqualTo(2);
        int previous = 0;
        for (JsonNode envelope : envelopes) {
            assertThat(envelope.get("eventId").asText()).matches(UUID_PATTERN);
            int sequence = envelope.get("sequence").asInt();
            assertThat(sequence).isGreaterThan(previous);
            previous = sequence;
        }
    }
    @Test
    void lastEventIdCursorReplaysOnlyLaterEvents() throws Exception {
        String threadId = createThread();
        String runId = createRun(threadId, "cursor check");
        waitForTerminal(runId);
        MvcResult listed = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId + "/events")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        int total = mapper.readTree(listed.getResponse().getContentAsString()).size();
        assertThat(total).isGreaterThanOrEqualTo(2);
        MvcResult started = mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId + "/events")
                        .accept(MediaType.TEXT_EVENT_STREAM).header("Last-Event-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        MvcResult done = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk()).andReturn();
        String body = done.getResponse().getContentAsString();
        assertThat(body).doesNotContain("id:1\n");
        assertThat(body).contains("id:2");
    }
    @Test
    void malformedCursorFailsClosed() throws Exception {
        String threadId = createThread();
        String runId = createRun(threadId, "cursor fail check");
        waitForTerminal(runId);
        mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId + "/events")
                        .accept(MediaType.TEXT_EVENT_STREAM).header("Last-Event-ID", "abc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/global-assistant/runs/" + runId + "/events")
                        .accept(MediaType.TEXT_EVENT_STREAM).header("Last-Event-ID", "-3"))
                .andExpect(status().isBadRequest());
    }
    @Test
    void concurrentAppendsKeepStrictlyIncreasingSequences() throws Exception {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(
                thread.id(), "concurrent stream", "v1", "v1", "fp");
        int writers = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(writers);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    runEvents.append(run.id(), "STATUS", Map.of("message", "w" + index));
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        var stored = runEvents.findByRun(run.id());
        assertThat(stored).hasSize(writers);
        assertThat(stored.stream().map(e -> e.sequence()).toList())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }
}
