package com.specagent.modelsettings;

import com.specagent.modelsettings.OpenCodeSettingsStatus;

public record OpenCodeSettingsResponse(boolean configured, String maskedKey, String selectedModel) {

    public static OpenCodeSettingsResponse from(OpenCodeSettingsStatus status) {
        return new OpenCodeSettingsResponse(status.configured(), status.maskedKey(), status.selectedModel());
    }
}
