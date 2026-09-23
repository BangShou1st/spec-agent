package com.specagent.assistant.runtime;

import com.specagent.common.ApiException;
import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantMessage;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunActiveException;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.conversation.GlobalAssistantThreadListItem;
import com.specagent.assistant.model.GlobalAssistantPromptRenderer;
import com.specagent.assistant.GlobalAssistantErrorCode;
import com.specagent.assistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.assistant.conversation.ConversationDeleteService;
import com.specagent.assistant.conversation.PendingTurn;
import com.specagent.assistant.runtime.RunDispatcher;
import com.specagent.assistant.conversation.SteerPendingException;
import com.specagent.assistant.conversation.ThreadActivity;
import com.specagent.assistant.conversation.ThreadActivityService;
import com.specagent.assistant.runtime.TurnHandoffService;
import com.specagent.assistant.tool.GlobalAssistantToolCatalog;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Application-owned orchestration: threads, runs, steer, stop, delete.
 * Delegates conversation, handoff, activity and runtime work to owners.
 *
 * <p>Lives in {@code com.specagent.assistant.runtime} rather than
 * {@code ...globalassistant.api}: it is use-case orchestration, and this move
 * also removes the package's reverse dependency on the outermost API package
 * (it used to import {@code api.common.ApiException}, which was the
 * {@code globalassistant -> api} edge of the
 * {@code agent -> model -> globalassistant -> api -> agent} package cycle).
 *
 * <p>{@link GlobalAssistantRunRepository} is still injected directly: the run
 * read path here needs {@code findById} plus the {@code requestCancel} write,
 * and {@link GlobalAssistantConversationService} exposes neither (it owns
 * threads, messages and working state), so there is no equivalent read path to
 * delegate to without widening that service's contract.
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
        } catch (com.specagent.assistant.conversation.SteerRejectedException ex) {
            throw switch (ex.reason()) {
                case BLANK -> ApiException.badRequest("MESSAGE_REQUIRED", "Message must not be blank");
                case TOO_LONG -> ApiException.badRequest("MESSAGE_TOO_LONG", "Message too long");
                case RUN_NOT_FOUND -> ApiException.notFound("RUN_NOT_FOUND", "Run not found");
                case THREAD_MISMATCH -> ApiException.notFound("RUN_NOT_FOUND", "Run not found");
                case STALE_TARGET -> ApiException.conflict(GlobalAssistantErrorCode.RUN_STALE, "Target run is stale; refresh and send as a new message");
                case INVALID -> ApiException.badRequest("STEER_INVALID", "Steer request invalid");
            };
        }
    }
    public ThreadActivity threadActivity(UUID threadId) {
        requireThread(threadId);
        return activity.read(threadId);
    }
    public ThreadActivity stopThread(UUID threadId) {
        requireThread(threadId);
        return handoff.stopThreadAtomically(threadId);
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
