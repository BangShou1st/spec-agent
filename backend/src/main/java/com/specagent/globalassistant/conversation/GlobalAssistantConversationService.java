package com.specagent.globalassistant.conversation;

import com.specagent.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversation owner: threads, messages, runs, working state, summary CAS.
 * Never calls the model, never selects tools, never emits SSE.
 */
@Service
public class GlobalAssistantConversationService {
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {
    };
    private final GlobalAssistantThreadRepository threads;
    private final GlobalAssistantMessageRepository messages;
    private final GlobalAssistantRunRepository runs;
    private final Json json;
    public GlobalAssistantConversationService(
            GlobalAssistantThreadRepository threads,
            GlobalAssistantMessageRepository messages,
            GlobalAssistantRunRepository runs,
            Json json) {
        this.threads = threads;
        this.messages = messages;
        this.runs = runs;
        this.json = json;
    }
    @Transactional
    public GlobalAssistantThread createThread() {
        return threads.create();
    }
    public Optional<GlobalAssistantThread> findThread(UUID threadId) {
        return threads.findById(threadId);
    }
    @Transactional
    public GlobalAssistantMessage appendUserMessage(UUID threadId, String content, UUID runId) {
        requireThread(threadId);
        return messages.append(threadId, GlobalAssistantMessage.Role.USER, content, runId);
    }
    @Transactional
    public GlobalAssistantMessage appendAssistantMessage(UUID threadId, String content, UUID runId) {
        requireThread(threadId);
        return messages.append(threadId, GlobalAssistantMessage.Role.ASSISTANT, content, runId);
    }
    @Transactional
    public GlobalAssistantRun createRun(UUID threadId, String promptVersion, String contextProjectionVersion, String toolCatalogFingerprint) {
        requireThread(threadId);
        return runs.create(threadId, promptVersion, contextProjectionVersion, toolCatalogFingerprint);
    }
    public java.util.List<GlobalAssistantMessage> listMessages(UUID threadId) {
        requireThread(threadId);
        return messages.findByThread(threadId);
    }
    public GlobalAssistantWorkingState readWorkingState(UUID threadId) {
        GlobalAssistantThread thread = requireThread(threadId);
        if (thread.workingStateJson() == null || thread.workingStateJson().isBlank()) {
            return GlobalAssistantWorkingState.empty();
        }
        try {
            Map<String, Object> map = json.read(thread.workingStateJson(), MAP_REF);
            return GlobalAssistantWorkingState.fromMap(map);
        } catch (IllegalStateException ex) {
            return GlobalAssistantWorkingState.empty();
        }
    }
    @Transactional
    public void writeWorkingState(UUID threadId, GlobalAssistantWorkingState state, int expectedVersion) {
        threads.updateWorkingState(threadId, state.toMap(), expectedVersion);
    }
    @Transactional
    public void writeSummary(UUID threadId, String summary, int expectedVersion) {
        threads.updateSummary(threadId, summary, expectedVersion);
    }
    private GlobalAssistantThread requireThread(UUID threadId) {
        return threads.findById(threadId)
                .orElseThrow(() -> new IllegalArgumentException("Global assistant thread not found: " + threadId));
    }
}
