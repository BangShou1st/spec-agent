package com.specagent.globalassistant.api;

import com.specagent.api.common.ApiException;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunActiveException;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntime;
import com.specagent.globalassistant.tool.GlobalAssistantToolCatalog;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;

/**
 * API-owned orchestration: threads, runs, cancel. Delegates conversation,
 * context, brain and runtime work to their owners.
 */
@Service
public class GlobalAssistantApplicationService {
    private final GlobalAssistantConversationService conversations;
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRuntime runtime;
    private final Executor gaExecutor;
    public GlobalAssistantApplicationService(GlobalAssistantConversationService conversations,
            GlobalAssistantRunRepository runs, GlobalAssistantRuntime runtime, Executor gaExecutor) {
        this.conversations = conversations;
        this.runs = runs;
        this.runtime = runtime;
        this.gaExecutor = gaExecutor;
    }
    public GlobalAssistantThread createThread() {
        return conversations.createThread();
    }
    public GlobalAssistantThread requireThread(UUID threadId) {
        return conversations.findThread(threadId)
                .orElseThrow(() -> ApiException.notFound("THREAD_NOT_FOUND", "Thread not found"));
    }
    public List<GlobalAssistantMessage> listMessages(UUID threadId) {
        requireThread(threadId);
        return conversations.listMessages(threadId);
    }
    public GlobalAssistantRun requireRun(UUID runId) {
        return runs.findById(runId)
                .orElseThrow(() -> ApiException.notFound("RUN_NOT_FOUND", "Run not found"));
    }
    public GlobalAssistantRun createRun(UUID threadId, String message,
            GlobalAssistantContextBuilder.UiRequest uiRequest) {
        requireThread(threadId);
        if (message == null || message.isBlank()) {
            throw ApiException.badRequest("MESSAGE_REQUIRED", "Message must not be blank");
        }
        String trimmed = message.trim();
        if (trimmed.length() > 4000) {
            throw ApiException.badRequest("MESSAGE_TOO_LONG", "Message too long");
        }
        GlobalAssistantRun run;
        try {
            run = conversations.createRun(threadId,
                    GlobalAssistantPromptRenderer.PROMPT_VERSION,
                    GlobalAssistantContextBuilder.CONTEXT_PROJECTION_VERSION,
                    GlobalAssistantToolCatalog.FINGERPRINT);
        } catch (GlobalAssistantRunActiveException ex) {
            throw ApiException.conflict(GlobalAssistantRunActiveException.CODE,
                    "Thread already hosts an active run");
        }
        conversations.appendUserMessage(threadId, trimmed, run.id());
        UUID runId = run.id();
        GlobalAssistantContextBuilder.UiRequest captured = uiRequest;
        gaExecutor.execute(() -> {
            try {
                runtime.executeRun(threadId, runId, trimmed, captured);
            } catch (Exception ignored) {
            }
        });
        return runs.findById(runId).orElseThrow();
    }
    public GlobalAssistantRun cancelRun(UUID runId) {
        GlobalAssistantRun run = requireRun(runId);
        if (run.status().isTerminal()) {
            return run;
        }
        runs.requestCancel(runId);
        return runs.findById(runId).orElseThrow();
    }
}
