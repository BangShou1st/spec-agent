package com.specagent.globalassistant.context;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityVisibilityService;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.conversation.GlobalAssistantWorkingState;
import com.specagent.globalassistant.tool.GlobalAssistantToolCatalog;
import com.specagent.globalassistant.tool.GlobalProjectSearchService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Projects bounded canonical facts into a model-visible context.
 * Never executes tools, never calls controllers, never mutates projects,
 * never decides the next tool.
 */
@Service
public class GlobalAssistantContextBuilder {
    public static final String CONTEXT_PROJECTION_VERSION = "v1";
    static final int MAX_RECENT_MESSAGES = 24;
    static final int MAX_MESSAGE_CHARS = 2000;
    static final int MAX_HINTS = 5;
    private final GlobalAssistantConversationService conversations;
    private final GlobalProjectSearchService search;
    private final CapabilityVisibilityService visibility;
    public GlobalAssistantContextBuilder(GlobalAssistantConversationService conversations,
            GlobalProjectSearchService search, CapabilityVisibilityService visibility) {
        this.conversations = conversations;
        this.search = search;
        this.visibility = visibility;
    }
    public GlobalAssistantContext build(UUID threadId, String currentRequest, UiRequest uiRequest) {
        GlobalAssistantThread thread = conversations.findThread(threadId)
                .orElseThrow(() -> new IllegalArgumentException("Global assistant thread not found: " + threadId));
        List<GlobalAssistantMessage> stored = conversations.listMessages(threadId);
        List<GlobalAssistantContext.ConversationTurn> recent = new ArrayList<>();
        int start = Math.max(0, stored.size() - MAX_RECENT_MESSAGES);
        for (int i = start; i < stored.size(); i++) {
            GlobalAssistantMessage message = stored.get(i);
            String content = message.content() == null ? "" : message.content();
            if (content.length() > MAX_MESSAGE_CHARS) {
                content = content.substring(0, MAX_MESSAGE_CHARS) + "\u2026";
            }
            recent.add(new GlobalAssistantContext.ConversationTurn(message.role().name(), content));
        }
        GlobalAssistantWorkingState workingState = conversations.readWorkingState(threadId);
        List<GlobalAssistantContext.ProjectHint> hints = search.listRecent(MAX_HINTS).stream()
                .map(c -> new GlobalAssistantContext.ProjectHint(
                        c.projectId().toString(), truncate(c.title(), 120), c.updatedAt()))
                .toList();
        List<CapabilityDescriptor> visible = visibility
                .visibleCapabilities(GlobalAssistantToolCatalog.queryContext()).stream()
                .filter(d -> GlobalAssistantToolCatalog.isAllowed(d.capabilityId()))
                .toList();
        GlobalAssistantContext.UiContext uiContext = new GlobalAssistantContext.UiContext(
                uiRequest == null || uiRequest.currentPage() == null ? "UNKNOWN" : uiRequest.currentPage(),
                uiRequest == null || uiRequest.selectedEntity() == null ? null
                        : new GlobalAssistantContext.SelectedEntity(
                                uiRequest.selectedEntity().type(), uiRequest.selectedEntity().id()));
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
