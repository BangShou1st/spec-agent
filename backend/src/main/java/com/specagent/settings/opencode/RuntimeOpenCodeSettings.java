package com.specagent.settings.opencode;

/** Backend-only projection consumed by the production model gateway. */
public record RuntimeOpenCodeSettings(String apiKey,
                                       String selectedModel,
                                       String credentialSource) {

    /** Keeps existing callers on the canonical product-database source. */
    public RuntimeOpenCodeSettings(String apiKey, String selectedModel) {
        this(apiKey, selectedModel, "database:opencode_settings");
    }

    /** Never allow accidental diagnostic logging to expose the provider key. */
    @Override
    public String toString() {
        return "RuntimeOpenCodeSettings[selectedModel=" + selectedModel
                + ", credentialSource=" + credentialSource + "]";
    }
}
