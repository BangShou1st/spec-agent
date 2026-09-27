package com.specagent.assistant.model;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.assistant.conversation.GlobalAssistantWorkingState;
import java.util.List;

/**
 * 文件名:GlobalAssistantContext.java
 *
 * 用途:喂给模型的"有界视图"——全局助手可见上下文的投影,也是提示词渲染
 * (GlobalAssistantPromptRenderer)的输入。只放当前请求、最近对话、对话摘要、
 * UI 状态、工作状态、最近项目线索和工具描述符等必要信息,
 * 绝不把完整目录、图谱或历史记录塞给模型。
 */
public record GlobalAssistantContext(
        String currentRequest,
        List<ConversationTurn> recentConversation,
        String conversationSummary,
        UiContext uiContext,
        GlobalAssistantWorkingState workingState,
        List<ProjectHint> recentProjectHints,
        List<CapabilityDescriptor> toolDescriptors) {
    public GlobalAssistantContext {
        recentConversation = recentConversation == null ? List.of() : List.copyOf(recentConversation);
        recentProjectHints = recentProjectHints == null ? List.of() : List.copyOf(recentProjectHints);
        toolDescriptors = toolDescriptors == null ? List.of() : List.copyOf(toolDescriptors);
    }
    public record ConversationTurn(String role, String content) {
    }
    public record UiContext(String currentPage, SelectedEntity selectedEntity) {
    }
    public record SelectedEntity(String type, String id) {
    }
    public record ProjectHint(String projectId, String title, String updatedAt) {
    }
}
