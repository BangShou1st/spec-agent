package com.specagent.globalassistant.api;

import com.specagent.api.common.ApiException;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunActiveException;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.conversation.GlobalAssistantThreadListItem;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.globalassistant.runtime.GlobalAssistantErrorCode;
import com.specagent.globalassistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.globalassistant.turn.ConversationDeleteService;
import com.specagent.globalassistant.turn.PendingTurn;
import com.specagent.globalassistant.turn.RunDispatcher;
import com.specagent.globalassistant.turn.SteerPendingException;
import com.specagent.globalassistant.turn.ThreadActivity;
import com.specagent.globalassistant.turn.ThreadActivityService;
import com.specagent.globalassistant.turn.TurnHandoffService;
import com.specagent.globalassistant.tool.GlobalAssistantToolCatalog;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * API-owned orchestration: threads, runs, steer, stop, delete.
 * Delegates conversation, handoff, activity and runtime work to owners.
 */
@Service
public class GlobalAssistantApplicationService {
    private static final Logger log = LoggerFactory.getLogger(GlobalAssistantApplicationService.class);
    private final GlobalAssistantConversationService conversations;
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final TurnHandoffService handoff;
    private final ThreadActivityService activity;
    private final ConversationDeleteService deletes;
    private final RunDispatcher dispatcher;
    public GlobalAssistantApplicationService(GlobalAssistantConversationService conversations,
            GlobalAssistantRunRepository runs,
            GlobalAssistantRunLifecycleService lifecycle, TurnHandoffService handoff,
            ThreadActivityService activity, ConversationDeleteService deletes, RunDispatcher dispatcher) {
        this.conversations = conversations;
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.handoff = handoff;
        this.activity = activity;
        this.deletes = deletes;
        this.dispatcher = dispatcher;
    }
    public GlobalAssistantThread createThread() {
        return conversations.createThread();
    }
    public List<GlobalAssistantThreadListItem> listThreads() {
        return conversations.listThreads();
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
        dispatcher.dispatch(threadId, runId, trimmed, uiRequest);
        return runs.findById(runId).orElseThrow();
    }
    public record SteerResult(PendingTurn pending, TurnHandoffService.Successor successor) {
    }
    public SteerResult steerRun(UUID targetRunId, String message, GlobalAssistantContextBuilder.UiRequest uiRequest) {
        GlobalAssistantRun target = requireRun(targetRunId);
        UUID threadId = target.threadId();
        requireThread(threadId);
        if (message == null || message.isBlank()) {
            throw ApiException.badRequest("MESSAGE_REQUIRED", "Message must not be blank");
        }
        if (message.trim().length() > 4000) {
            throw ApiException.badRequest("MESSAGE_TOO_LONG", "Message too long");
        }
        try {
            var accepted = handoff.acceptSteer(threadId, targetRunId, message, uiRequest);
            accepted.successor().ifPresent(s -> dispatcher.dispatch(s.run().threadId(), s.run().id(), s.message(), s.uiRequest()));
            return new SteerResult(accepted.pendingTurn(), accepted.successor().orElse(null));
        } catch (SteerPendingException ex) {
            throw ApiException.conflict(GlobalAssistantErrorCode.STEER_PENDING, "Previous steer is still taking effect");
        } catch (IllegalArgumentException ex) {
            String msg = String.valueOf(ex.getMessage());
            if (msg.contains("too long")) {
                throw ApiException.badRequest("MESSAGE_TOO_LONG", "Message too long");
            }
            if (msg.contains("must not be blank")) {
                throw ApiException.badRequest("MESSAGE_REQUIRED", "Message must not be blank");
            }
            if (msg.contains("Run not found") || msg.contains("Thread not found") || msg.contains("does not belong")) {
                throw ApiException.notFound("RUN_NOT_FOUND", "Run not found");
            }
            throw ApiException.badRequest("STEER_INVALID", "Steer request invalid");
        }
    }
    public ThreadActivity threadActivity(UUID threadId) {
        requireThread(threadId);
        return activity.read(threadId);
    }
    public ThreadActivity stopThread(UUID threadId) {
        requireThread(threadId);
        handoff.discardUnresolved(threadId);
        var active = runs.findActiveByThread(threadId);
        active.ifPresent(run -> runs.requestCancel(run.id()));
        return activity.read(threadId);
    }
    public void deleteThread(UUID threadId) {
        requireThread(threadId);
        try {
            deletes.deleteThread(threadId);
        } catch (IllegalStateException ex) {
            if ("THREAD_ACTIVE".equals(ex.getMessage())) {
                throw ApiException.conflict(GlobalAssistantErrorCode.THREAD_ACTIVE, "Thread hosts active work");
            }
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw ApiException.notFound("THREAD_NOT_FOUND", "Thread not found");
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
