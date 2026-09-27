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
 * 文件名:GlobalAssistantRunLifecycleService.java
 *
 * 用途:run 生命周期状态迁移的事务性所有者。Runtime 只负责编排;
 * 每一次状态迁移连同它的公开事件都在这里原子提交。
 * 任何失败都不吞掉:竞争落败会以类型化异常浮现,
 * 落败方不发任何相互矛盾的事件。
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
    /** 完成时携带 run 上记录的请求期供应商/模型快照。 */
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
        // 权威助手消息只持久化一次。用户可见正文此前已作为 ANSWER_DELTA
        // 瞬态事件流式输出;这里不再发全文 delta。
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
        // 澄清问题的正文(如有)此前已作为 ANSWER_DELTA 瞬态流式输出。
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

    /** 仅装饰性的模型归属;持久化时解析,绝不打断状态迁移。 */
    private String attributionProvider() {
        return modelTarget.resolveActive().providerLabel();
    }

    private String attributionModel() {
        return modelTarget.resolveActive().modelId();
    }
}
