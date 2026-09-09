package com.specagent.globalassistant.model;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.globalassistant.context.GlobalAssistantContext;
import com.specagent.model.inference.ModelInferenceMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Provider-neutral prompt rendering. Short, stable, tool-oriented.
 * No phrase-to-tool mappings, no benchmark examples, no few-shot patches.
 */
@Component
public class GlobalAssistantPromptRenderer {
    public static final String PROMPT_VERSION = "v1";
    static final String SYSTEM_PROMPT = """
            You are Spec Agent's application-level assistant.

            Help the user operate Spec Agent using the currently available tools and \
            structured application context.

            You do not replace the Project Agent for requirement exploration.

            Rules:
            - Choose tools by semantic need and context, never by keyword overlap alone.
            - If structured context already identifies the target, use it.
            - If current application truth is needed, use the appropriate tool instead of \
              trusting old conversation text.
            - Ask the user when essential ambiguity remains.
            - Do not invent project IDs, URLs, capabilities, or tool results.
            - Use only tools actually provided in the current request.
            - Stop once the user's application-level goal is achieved.
            - Avoid unnecessary tool calls.
            - Destructive or privileged operations are controlled by Runtime policy.
            - Use tools when canonical application state is required.
            - Do not invent project IDs.
            - Ask the user when ambiguity materially prevents a safe action.
            - Use tool observations as fresh truth.
            - Return only the decision schema.
            Decision schema (JSON only, no other text):
            {"assistantText": "...", "statusText": "...", \
             "toolRequest": {"capabilityId": "...", "arguments": {...}} | null, \
             "uiAction": {"destination": "PROJECT|PROJECTS|SKILLS|CONNECTIONS|SETTINGS", "resourceId": "..."} | null, \
             "requiresUserInput": false, "done": true}
            One primary tool per decision at most. Set done=true only when no further \
            tool is needed and the final text is ready. Set requiresUserInput=true when \
            ambiguity materially prevents a safe action; then done=true as well.
            """;
    public List<ModelInferenceMessage> render(GlobalAssistantContext context, List<Map<String, Object>> observations) {
        List<ModelInferenceMessage> messages = new ArrayList<>();
        messages.add(new ModelInferenceMessage("system", SYSTEM_PROMPT));
        StringBuilder user = new StringBuilder();
        user.append("Current request:\n").append(nullSafe(context.currentRequest())).append("\n\n");
        if (context.conversationSummary() != null && !context.conversationSummary().isBlank()) {
            user.append("Conversation summary:\n").append(truncate(context.conversationSummary(), 1500)).append("\n\n");
        }
        if (!context.recentConversation().isEmpty()) {
            user.append("Recent conversation (oldest first):\n");
            for (GlobalAssistantContext.ConversationTurn turn : context.recentConversation()) {
                user.append(turn.role()).append(": ").append(truncate(turn.content(), 1000)).append("\n");
            }
            user.append("\n");
        }
        if (context.uiContext() != null) {
            user.append("UI context: page=").append(nullSafe(context.uiContext().currentPage()));
            if (context.uiContext().selectedEntity() != null) {
                user.append(", selected=" + context.uiContext().selectedEntity().type()
                        + ":" + context.uiContext().selectedEntity().id());
            }
            user.append("\n\n");
        }
        if (context.workingState() != null) {
            user.append("Working state: goal=").append(nullSafe(context.workingState().goal()));
            if (context.workingState().lastResolvedProjectId() != null) {
                user.append(", lastResolved=" + context.workingState().lastResolvedProjectId());
            }
            if (!context.workingState().candidateProjects().isEmpty()) {
                user.append(", candidates=" + context.workingState().candidateProjects().size());
            }
            if (context.workingState().waitingFor() != null) {
                user.append(", waitingFor=" + context.workingState().waitingFor());
            }
            user.append("\n\n");
        }
        if (!context.recentProjectHints().isEmpty()) {
            user.append("Recent project hints (hints only, not truth; verify with tools when identity matters):\n");
            for (GlobalAssistantContext.ProjectHint hint : context.recentProjectHints()) {
                user.append("- " + hint.title() + " (" + hint.projectId() + ")\n");
            }
            user.append("\n");
        }
        user.append("Available tools:\n");
        for (CapabilityDescriptor descriptor : context.toolDescriptors()) {
            user.append("- " + descriptor.capabilityId() + ": " + descriptor.description() + "\n");
        }
        if (observations != null && !observations.isEmpty()) {
            user.append("\nTool observations (fresh truth):\n");
            for (Map<String, Object> observation : observations) {
                user.append(truncate(String.valueOf(observation), 2000)).append("\n");
            }
        }
        messages.add(new ModelInferenceMessage("user", user.toString()));
        return messages;
    }
    public List<ModelInferenceMessage> renderSummary(String recentText) {
        return List.of(
                new ModelInferenceMessage("system",
                        "Summarize the conversation for continuity. Keep only goals, unresolved references, "
                        + "resolved project identities, and important tool outcomes. "
                        + "Never keep private reasoning, chain-of-thought, provider payloads, status text, or tool card copy. "
                        + "Return plain text, at most 800 characters."),
                new ModelInferenceMessage("user", truncate(recentText == null ? "" : recentText, 6000)));
    }
    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\u2026";
    }
}
