package com.specagent.agent;

import com.specagent.model.contract.ModelPrompt;
import org.springframework.stereotype.Component;

/**
 * Renders the production prompt for a {@link ModelRequest}.
 *
 * <p>The renderer is pure: it never touches the database, never reads live
 * state, and never builds context projections. The runtime passes the frozen
 * projection inside {@link ModelRequest#inputJson()}; the renderer only places
 * it into the user prompt as data.
 */
@Component
public class AgentPromptRenderer {

    private final TaskPromptCatalog catalog;

    public AgentPromptRenderer(TaskPromptCatalog catalog) {
        this.catalog = catalog;
    }

    public ModelPrompt render(ModelRequest request) {
        return catalog.promptFor(request.taskType(), request.inputJson());
    }
}