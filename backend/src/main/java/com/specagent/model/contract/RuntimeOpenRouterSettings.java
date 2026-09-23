package com.specagent.model.contract;

/**
 * Immutable runtime projection of the stored OpenRouter configuration for
 * the inference gateway. Carries only what a request needs; the provider key
 * never appears in diagnostics.
 */
public record RuntimeOpenRouterSettings(String apiKey,
                                         String selectedModel) {

    /** Never allow accidental diagnostic logging to expose the provider key. */
    @Override
    public String toString() {
        return "RuntimeOpenRouterSettings[selectedModel=" + selectedModel + "]";
    }
}
