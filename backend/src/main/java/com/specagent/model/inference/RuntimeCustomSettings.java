package com.specagent.model.inference;

/**
 * Immutable runtime projection of the stored custom-provider configuration
 * for the inference gateway: explicit API format, normalized base URL,
 * selected model and key. The provider key never appears in diagnostics.
 */
public record RuntimeCustomSettings(String apiFormat,
                                     String baseUrl,
                                     String apiKey,
                                     String selectedModel) {

    /** Never allow accidental diagnostic logging to expose the provider key. */
    @Override
    public String toString() {
        return "RuntimeCustomSettings[apiFormat=" + apiFormat
                + ", baseUrl=" + baseUrl
                + ", selectedModel=" + selectedModel + "]";
    }
}
