package com.specagent.model.inference;

/**
 * Narrow runtime-settings seam for the custom-provider inference gateway:
 * the stored configuration only when it is present AND validated at its
 * current revision (fail closed otherwise, exactly like the historical
 * requireStored + requireActivatable sequence). The API format stays an
 * explicit stored value — never auto-detected. Implemented by the settings
 * side.
 */
public interface CustomRuntimeSettingsPort {

    /** Activatable custom settings, or NOT_CONFIGURED failure. */
    RuntimeCustomSettings requireRuntimeSettings();
}
