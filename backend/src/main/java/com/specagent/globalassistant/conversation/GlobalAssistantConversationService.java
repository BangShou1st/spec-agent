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
    private final GlobalAssistantThreadListRepository threadLists;
    public GlobalAssistantConversationService(
            GlobalAssistantThreadRepository threads,
            GlobalAssistantMessageRepository messages,
            GlobalAssistantRunRepository runs,
            Json json,
            GlobalAssistantThreadListRepository threadLists) {
        this.threads = threads;
        this.messages = messages;
        this.runs = runs;
        this.json = json;
        this.threadLists = threadLists;
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
    /**
     * Atomic run + current USER message creation: both commit or both roll
     * back, so no orphan active run survives without its user message.
     * Callers schedule execution only after this transaction commits.
     */
    @Transactional
    public GlobalAssistantRun createRunWithUserMessage(UUID threadId, String content, String promptVersion,
            String contextProjectionVersion, String toolCatalogFingerprint) {
        requireThread(threadId);
        GlobalAssistantRun run =
                runs.create(threadId, promptVersion, contextProjectionVersion, toolCatalogFingerprint);
        messages.append(threadId, GlobalAssistantMessage.Role.USER, content, run.id());
        return run;
    }
    public java.util.List<GlobalAssistantMessage> listMessages(UUID threadId) {
        requireThread(threadId);
        return messages.findByThread(threadId);
    }
    public java.util.List<GlobalAssistantThreadListItem> listThreads() {
        return threadLists.listRecent(GlobalAssistantConversationLibrary.LIST_LIMIT);
    }
    public GlobalAssistantWorkingState readWorkingState(UUID threadId) {
        GlobalAssistantThread thread = requireThread(threadId);
        if (thread.workingStateJson() == null || thread.workingStateJson().isBlank()) {
            return GlobalAssistantWorkingState.empty();
        }
        Map<String, Object> map;
        try {
            map = json.read(thread.workingStateJson(), MAP_REF);
        } catch (IllegalStateException ex) {
            throw new IllegalStateException("Working-state storage is corrupt for thread: " + threadId);
        }
        if (map == null) {
            return GlobalAssistantWorkingState.empty();
        }
        try {
            return GlobalAssistantWorkingState.fromMap(map);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Working-state storage is corrupt for thread: " + threadId);
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
