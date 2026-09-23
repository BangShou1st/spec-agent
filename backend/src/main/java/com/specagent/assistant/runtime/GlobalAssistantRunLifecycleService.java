package com.specagent.assistant.runtime;

import com.specagent.assistant.GlobalAssistantErrorCode;

import com.specagent.assistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantEventType;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunStatus;
import com.specagent.assistant.model.GlobalAssistantModelTargetResolver;
import com.specagent.assistant.runtime.GlobalAssistantRunEventService;
import com.specagent.assistant.runtime.RunTerminalEvent;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * Transactional run lifecycle owner. The Runtime only orchestrates;
 * every state transition plus its public events commits atomically here.
 * Nothing is swallowed: a lost race surfaces as a typed exception and the
 * loser emits nothing contradictory.
 */
@Service
public class GlobalAssistantRunLifecycleService {
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantConversationService conversations;
    private final GlobalAssistantRunEventService events;
    private final GlobalAssistantModelTargetResolver modelTarget;
    private final ApplicationEventPublisher publisher;
    public GlobalAssistantRunLifecycleService(GlobalAssistantRunRepository runs,
            GlobalAssistantConversationService conversations,
            GlobalAssistantRunEventService events,
            GlobalAssistantModelTargetResolver modelTarget,
            ApplicationEventPublisher publisher) {
        this.runs = runs;
        this.conversations = conversations;
        this.events = events;
        this.modelTarget = modelTarget;
        this.publisher = publisher;
    }
    @Transactional
    public void claimAndStart(UUID runId) {
        GlobalAssistantRun run = runs.lockById(runId);
        if (run.status() != GlobalAssistantRunStatus.CREATED) {
            throw new GlobalAssistantRunClaimedException(run.status());
        }
        runs.markRunning(runId);
        events.append(runId, GlobalAssistantEventType.RUN_STARTED, Map.of("threadId", run.threadId().toString()));
    }
    @Transactional
    public void completeWithAssistant(UUID threadId, UUID runId, String text) {
        completeWithAssistantAndUiAction(threadId, runId, text, null, null);
    }
    /** Completion with the request-time provider/model snapshot from the run. */
    @Transactional
    public void completeWithAssistant(UUID threadId, UUID runId, String text,
            String providerLabel, String modelId) {
        completeWithAssistantAndUiAction(threadId, runId, text, null, null, providerLabel, modelId);
    }
    @Transactional
    public void completeWithAssistantAndUiAction(UUID threadId, UUID runId, String text,
            String uiDestination, String uiResourceId) {
        completeWithAssistantAndUiAction(threadId, runId, text, uiDestination, uiResourceId,
                attributionProvider(), attributionModel());
    }
    @Transactional
    public void completeWithAssistantAndUiAction(UUID threadId, UUID runId, String text,
            String uiDestination, String uiResourceId, String providerLabel, String modelId) {
        // The authoritative message is persisted exactly once. User-visible prose
        // already streamed as ANSWER_DELTA transients; no full-text delta here.
        conversations.appendAssistantMessage(threadId, text, runId, providerLabel, modelId);
        events.append(runId, GlobalAssistantEventType.ASSISTANT_COMPLETED, Map.of());
        if (uiDestination != null) {
            if (uiResourceId != null) {
                events.append(runId, GlobalAssistantEventType.UI_ACTION,
                        Map.of("destination", uiDestination, "resourceId", uiResourceId));
            } else {
                events.append(runId, GlobalAssistantEventType.UI_ACTION, Map.of("destination", uiDestination));
            }
        }
        events.append(runId, GlobalAssistantEventType.RUN_COMPLETED, Map.of());
        runs.terminalize(runId, GlobalAssistantRunStatus.COMPLETED, null);
        publishTerminal(threadId, runId, "COMPLETED");
    }
    @Transactional
    public void completeForClarification(UUID threadId, UUID runId, String question) {
        completeForClarification(threadId, runId, question, attributionProvider(), attributionModel());
    }
    @Transactional
    public void completeForClarification(UUID threadId, UUID runId, String question,
            String providerLabel, String modelId) {
        // Question prose already streamed as ANSWER_DELTA transients, if any.
        conversations.appendAssistantMessage(threadId, question, runId, providerLabel, modelId);
        events.append(runId, GlobalAssistantEventType.ASSISTANT_COMPLETED, Map.of());
        events.append(runId, GlobalAssistantEventType.USER_INPUT_REQUIRED, Map.of("question", question));
        events.append(runId, GlobalAssistantEventType.RUN_COMPLETED, Map.of());
        runs.terminalize(runId, GlobalAssistantRunStatus.COMPLETED, null);
        publishTerminal(threadId, runId, "COMPLETED");
    }
    @Transactional
    public void failWithAssistant(UUID threadId, UUID runId, String text, String errorCode, String reason) {
        failWithAssistant(threadId, runId, text, errorCode, reason,
                attributionProvider(), attributionModel());
    }
    @Transactional
    public void failWithAssistant(UUID threadId, UUID runId, String text, String errorCode, String reason,
            String providerLabel, String modelId) {
        conversations.appendAssistantMessage(threadId, text, runId, providerLabel, modelId);
        events.append(runId, GlobalAssistantEventType.ASSISTANT_DELTA, Map.of("text", text));
        events.append(runId, GlobalAssistantEventType.ASSISTANT_COMPLETED, Map.of());
        events.append(runId, GlobalAssistantEventType.RUN_FAILED,
                Map.of("errorCode", errorCode, "reason", reason == null ? "" : reason));
        runs.terminalize(runId, GlobalAssistantRunStatus.FAILED, errorCode);
        publishTerminal(threadId, runId, "FAILED");
    }
    @Transactional
    public void cancelAndTerminalize(UUID runId) {
        GlobalAssistantRun run = runs.lockById(runId);
        events.append(runId, GlobalAssistantEventType.RUN_CANCELLED, Map.of());
        runs.terminalize(runId, GlobalAssistantRunStatus.CANCELLED, GlobalAssistantErrorCode.RUN_CANCELLED);
        publishTerminal(run.threadId(), runId, "CANCELLED");
    }
    @Transactional
    public void interruptAndTerminalize(UUID runId) {
        GlobalAssistantRun run = runs.lockById(runId);
        events.append(runId, GlobalAssistantEventType.RUN_FAILED, Map.of(
                "errorCode", GlobalAssistantErrorCode.RUN_INTERRUPTED,
                "reason", "The previous process stopped before this run finished."));
        runs.terminalize(runId, GlobalAssistantRunStatus.FAILED, GlobalAssistantErrorCode.RUN_INTERRUPTED);
        publishTerminal(run.threadId(), runId, "FAILED");
    }
    private void publishTerminal(UUID threadId, UUID runId, String status) {
        publisher.publishEvent(new RunTerminalEvent(threadId, runId, status));
    }

    /** Cosmetic attribution; resolved at persist time, never breaks the transition. */
    private String attributionProvider() {
        return modelTarget.resolveActive().providerLabel();
    }

    private String attributionModel() {
        return modelTarget.resolveActive().modelId();
    }
}
