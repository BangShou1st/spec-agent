package com.specagent.globalassistant.runtime;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantEventType;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
import com.specagent.globalassistant.stream.GlobalAssistantRunEventService;
import com.specagent.globalassistant.turn.RunTerminalEvent;
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
    private final ApplicationEventPublisher publisher;
    public GlobalAssistantRunLifecycleService(GlobalAssistantRunRepository runs,
            GlobalAssistantConversationService conversations,
            GlobalAssistantRunEventService events,
            ApplicationEventPublisher publisher) {
        this.runs = runs;
        this.conversations = conversations;
        this.events = events;
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
    @Transactional
    public void completeWithAssistantAndUiAction(UUID threadId, UUID runId, String text,
            String uiDestination, String uiResourceId) {
        conversations.appendAssistantMessage(threadId, text, runId);
        events.append(runId, GlobalAssistantEventType.ASSISTANT_DELTA, Map.of("text", text));
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
        conversations.appendAssistantMessage(threadId, question, runId);
        events.append(runId, GlobalAssistantEventType.ASSISTANT_DELTA, Map.of("text", question));
        events.append(runId, GlobalAssistantEventType.ASSISTANT_COMPLETED, Map.of());
        events.append(runId, GlobalAssistantEventType.USER_INPUT_REQUIRED, Map.of("question", question));
        events.append(runId, GlobalAssistantEventType.RUN_COMPLETED, Map.of());
        runs.terminalize(runId, GlobalAssistantRunStatus.COMPLETED, null);
        publishTerminal(threadId, runId, "COMPLETED");
    }
    @Transactional
    public void failWithAssistant(UUID threadId, UUID runId, String text, String errorCode, String reason) {
        conversations.appendAssistantMessage(threadId, text, runId);
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
}
