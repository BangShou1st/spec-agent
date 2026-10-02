package com.specagent.assistant.runtime;

import org.springframework.stereotype.Service;

/** Host-owned UI hints. No legacy prompt, history or decision protocol. */
@Service
public class GaHostContext {
    public static final String PROMPT_VERSION = "langchain-ga.v1";
    public static final String CONTEXT_PROJECTION_VERSION = "ga-host.v1";
    public record UiRequest(String currentPage, SelectedRef selectedEntity) {
        public record SelectedRef(String type, String id) {}
    }
    public record SelectedEntity(String type, String id) {}
    public record UiContext(String currentPage, SelectedEntity selectedEntity) {}
    private final GlobalAssistantUiActionValidator validator;
    public GaHostContext(GlobalAssistantUiActionValidator validator) { this.validator = validator; }
    public UiContext project(UiRequest request) {
        SelectedEntity selected = null;
        if (request != null && request.selectedEntity() != null) {
            var ref = request.selectedEntity();
            selected = validator.validSelectedProject(ref.type(), ref.id())
                    .map(id -> new SelectedEntity("PROJECT", id.toString())).orElse(null);
        }
        return new UiContext(request == null || request.currentPage() == null ? "UNKNOWN" : request.currentPage(), selected);
    }
}
