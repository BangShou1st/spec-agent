package com.specagent.model.inference;

/**
 * Narrow runtime-settings seam for the OpenRouter inference gateway: the
 * stored configuration only when it is present AND validated at its current
 * revision (fail closed otherwise, exactly like the historical
 * requireStored + requireActivatable sequence). Implemented by the settings
 * side.
 */
public interface OpenRouterRuntimeSettingsPort {

    /** Activatable OpenRouter settings, or NOT_CONFIGURED failure. */
    RuntimeOpenRouterSettings requireRuntimeSettings();
}
