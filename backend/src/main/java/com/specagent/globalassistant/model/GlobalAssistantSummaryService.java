package com.specagent.globalassistant.model;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.conversation.GlobalAssistantVersionConflictException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
/**
 * Threshold-based rolling summary loop. Summarizes bounded evicted facts
 * through the same provider-neutral gateway; never every round, never
 * unbounded input. A failed summary never breaks the completed user run
 * and never overwrites the previous summary; the next threshold retries.
 */
@Service
public class GlobalAssistantSummaryService {
    static final int MAX_SUMMARY_CHARS = 1500;
    private static final Logger log = LoggerFactory.getLogger(GlobalAssistantSummaryService.class);
    private final GlobalAssistantConversationService conversations;
    private final GlobalAssistantBrain brain;
    public GlobalAssistantSummaryService(GlobalAssistantConversationService conversations,
            GlobalAssistantBrain brain) {
        this.conversations = conversations;
        this.brain = brain;
    }
    public boolean maybeSummarize(UUID threadId, UUID runId) {
        GlobalAssistantThread thread = conversations.findThread(threadId).orElse(null);
        if (thread == null) {
            return false;
        }
        List<GlobalAssistantMessage> messages = conversations.listMessages(threadId);
        int alreadySummarized = com.specagent.globalassistant.conversation.GlobalAssistantConversationWindowPolicy
                .summarizedCount(thread.summaryVersion());
        int chunkSize = com.specagent.globalassistant.conversation.GlobalAssistantConversationWindowPolicy
                .SUMMARY_CHUNK_SIZE;
        int evictedCount = com.specagent.globalassistant.conversation.GlobalAssistantConversationWindowPolicy
                .evictedCount(messages.size());
        if (evictedCount < alreadySummarized + chunkSize) {
            return false;
        }
        List<GlobalAssistantMessage> chunk = messages.subList(alreadySummarized, alreadySummarized + chunkSize);
        StringBuilder input = new StringBuilder();
        if (thread.summary() != null && !thread.summary().isBlank()) {
            input.append("Previous summary:\n").append(bound(thread.summary(), 800)).append("\n\n");
        }
        input.append("New conversation facts (oldest first):\n");
        for (GlobalAssistantMessage message : chunk) {
            String content = message.content() == null ? "" : message.content();
            input.append(message.role().name()).append(": ").append(bound(content, 500)).append("\n");
        }
        String summary;
        try {
            summary = brain.summarize(runId, input.toString());
        } catch (GlobalAssistantModelException ex) {
            log.warn("Global assistant summary model call failed: threadId={} error={}",
                    threadId, ex.errorCode());
            return false;
        } catch (RuntimeException ex) {
            log.warn("Global assistant summary model call failed: threadId={} error={}",
                    threadId, ex.getClass().getSimpleName());
            return false;
        }
        if (summary == null || summary.isBlank()) {
            return false;
        }
        try {
            conversations.writeSummary(threadId, bound(summary.trim(), MAX_SUMMARY_CHARS), thread.summaryVersion());
        } catch (GlobalAssistantVersionConflictException ex) {
            log.debug("Global assistant summary CAS conflict, will retry at next threshold: threadId={}", threadId);
            return false;
        }
        return true;
    }
    private String bound(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
