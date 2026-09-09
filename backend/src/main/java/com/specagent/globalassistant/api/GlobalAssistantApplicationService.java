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
import com.specagent.globalassistant.runtime.GlobalAssistantErrorCode;
import com.specagent.globalassistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntime;
import com.specagent.globalassistant.tool.GlobalAssistantToolCatalog;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * API-owned orchestration: threads, runs, cancel. Delegates conversation,
 * context, brain and runtime work to their owners.
 */
@Service
public class GlobalAssistantApplicationService {
    private static final Logger log = LoggerFactory.getLogger(GlobalAssistantApplicationService.class);
    private final GlobalAssistantConversationService conversations;
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRuntime runtime;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final Executor gaExecutor;
    public GlobalAssistantApplicationService(GlobalAssistantConversationService conversations,
            GlobalAssistantRunRepository runs, GlobalAssistantRuntime runtime,
            GlobalAssistantRunLifecycleService lifecycle, Executor gaExecutor) {
        this.conversations = conversations;
        this.runs = runs;
        this.runtime = runtime;
        this.lifecycle = lifecycle;
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
    public java.util.Optional<GlobalAssistantRun> findRun(UUID runId) {
        return runs.findById(runId);
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
            run = conversations.createRunWithUserMessage(threadId, trimmed,
                    GlobalAssistantPromptRenderer.PROMPT_VERSION,
                    GlobalAssistantContextBuilder.CONTEXT_PROJECTION_VERSION,
                    GlobalAssistantToolCatalog.FINGERPRINT);
        } catch (GlobalAssistantRunActiveException ex) {
            throw ApiException.conflict(GlobalAssistantRunActiveException.CODE,
                    "Thread already hosts an active run");
        }
        UUID runId = run.id();
        GlobalAssistantContextBuilder.UiRequest captured = uiRequest;
        try {
            gaExecutor.execute(() -> executeSafely(threadId, runId, trimmed, captured));
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            log.warn("Global assistant executor saturated, failing queued run: runId={}", runId);
            lifecycle.failWithAssistant(threadId, runId,
                    "The assistant is busy right now, please try again.",
                    GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED, "Executor saturated");
        }
        return runs.findById(runId).orElseThrow();
    }
    private void executeSafely(UUID threadId, UUID runId, String message,
            GlobalAssistantContextBuilder.UiRequest uiRequest) {
        try {
            runtime.executeRun(threadId, runId, message, uiRequest);
        } catch (Exception ex) {
            log.warn("Global assistant run failed unexpectedly: runId={} error={}",
                    runId, ex.getClass().getSimpleName());
            try {
                var run = runs.findById(runId);
                if (run.isPresent() && run.get().status().isActive()) {
                    lifecycle.failWithAssistant(threadId, runId,
                            "I couldn't complete that step.",
                            GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "Unexpected execution failure");
                }
            } catch (Exception terminalEx) {
                log.warn("Global assistant run terminalization failed: runId={} error={}",
                        runId, terminalEx.getClass().getSimpleName());
            }
        }
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
