package com.specagent.settings.opencode;

/** Safe settings projection for browser/API consumers. */
public record OpenCodeSettingsStatus(
        boolean configured,
        String maskedKey,
        String selectedModel) {

    public static OpenCodeSettingsStatus unconfigured() {
        return new OpenCodeSettingsStatus(false, null, null);
    }
}
