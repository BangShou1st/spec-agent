package com.specagent.globalassistant.context;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.globalassistant.conversation.GlobalAssistantWorkingState;
import java.util.List;

/**
 * Bounded model-visible projection. Never the full catalog/graph/history.
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
