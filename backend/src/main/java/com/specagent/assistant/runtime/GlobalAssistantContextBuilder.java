package com.specagent.assistant.runtime;

import com.specagent.assistant.model.GlobalAssistantContext;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantMessage;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.conversation.GlobalAssistantWorkingState;
import com.specagent.assistant.tool.GlobalProjectSearchService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 文件名:GlobalAssistantContextBuilder.java
 *
 * 用途:把有界的权威事实投影成模型可见的 {@link GlobalAssistantContext}——
 * 组装最近对话窗口、对话摘要、UI 状态、工作状态、最近项目线索与工具描述符,
 * 供提示词渲染使用。它绝不执行工具、绝不调用控制器、绝不修改项目,
 * 也不决定下一个工具是什么。
 */
@Service
public class GlobalAssistantContextBuilder {
    public static final String CONTEXT_PROJECTION_VERSION = "v1";
    static final int MAX_MESSAGE_CHARS = 2000;
    static final int MAX_HINTS = 5;
    private final GlobalAssistantConversationService conversations;
    private final GlobalProjectSearchService search;
    private final com.specagent.assistant.tool.GlobalAssistantCatalogService catalog;
    private final com.specagent.assistant.runtime.GlobalAssistantUiActionValidator uiValidator;
    public GlobalAssistantContextBuilder(GlobalAssistantConversationService conversations,
            GlobalProjectSearchService search,
            com.specagent.assistant.tool.GlobalAssistantCatalogService catalog,
            com.specagent.assistant.runtime.GlobalAssistantUiActionValidator uiValidator) {
        this.conversations = conversations;
        this.search = search;
        this.catalog = catalog;
        this.uiValidator = uiValidator;
    }
    public GlobalAssistantContext build(UUID threadId, String currentRequest, UiRequest uiRequest) {
        return build(threadId, null, currentRequest, uiRequest);
    }
    /**
     * 构建有界投影。当前 run 的 USER 消息已经作为 {@code currentRequest}
     * 携带,因此会从最近历史中排除以免重复;更早 run 的历史照常保留。
     */
    public GlobalAssistantContext build(UUID threadId, UUID currentRunId, String currentRequest,
            UiRequest uiRequest) {
        GlobalAssistantThread thread = conversations.findThread(threadId)
                .orElseThrow(() -> new IllegalArgumentException("Global assistant thread not found: " + threadId));
        List<GlobalAssistantMessage> stored = conversations.listMessages(threadId);
        List<GlobalAssistantContext.ConversationTurn> recent = new ArrayList<>();
        int start = com.specagent.assistant.conversation.GlobalAssistantConversationWindowPolicy
                .recentStart(stored.size(), thread.summaryVersion());
        for (int i = start; i < stored.size(); i++) {
            GlobalAssistantMessage message = stored.get(i);
            if (currentRunId != null && message.role() == GlobalAssistantMessage.Role.USER
                    && currentRunId.equals(message.runId())) {
                continue;
            }
            String content = message.content() == null ? "" : message.content();
            if (content.length() > MAX_MESSAGE_CHARS) {
                content = content.substring(0, MAX_MESSAGE_CHARS) + "...";
            }
            recent.add(new GlobalAssistantContext.ConversationTurn(message.role().name(), content));
        }
        GlobalAssistantWorkingState workingState = conversations.readWorkingState(threadId);
        List<GlobalAssistantContext.ProjectHint> hints = search.listRecent(MAX_HINTS).stream()
                .map(c -> new GlobalAssistantContext.ProjectHint(
                        c.projectId().toString(), truncate(c.title(), 120), c.updatedAt()))
                .toList();
        List<CapabilityDescriptor> visible = catalog.modelCatalog();
        GlobalAssistantContext.SelectedEntity selected = null;
        if (uiRequest != null && uiRequest.selectedEntity() != null) {
            String selectedType = uiRequest.selectedEntity().type();
            String selectedId = uiRequest.selectedEntity().id();
            if ("PROJECT".equalsIgnoreCase(selectedType == null ? "" : selectedType.trim())) {
                selected = uiValidator.validSelectedProject(selectedType, selectedId)
                        .map(valid -> new GlobalAssistantContext.SelectedEntity("PROJECT", valid.toString()))
                        .orElse(null);
            } else if (selectedType != null && selectedId != null) {
                selected = new GlobalAssistantContext.SelectedEntity(selectedType, selectedId);
            }
        }
        GlobalAssistantContext.UiContext uiContext = new GlobalAssistantContext.UiContext(
                uiRequest == null || uiRequest.currentPage() == null ? "UNKNOWN" : uiRequest.currentPage(),
                selected);
        return new GlobalAssistantContext(
                currentRequest == null ? "" : truncate(currentRequest, MAX_MESSAGE_CHARS),
                recent,
                thread.summary(),
                uiContext,
                workingState,
                hints,
                visible);
    }
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "\u2026";
    }
    public record UiRequest(String currentPage, SelectedRef selectedEntity) {
        public record SelectedRef(String type, String id) {
        }
    }
}
