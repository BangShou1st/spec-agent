package com.specagent.model.inference;

/**
 * Narrow runtime-settings seam for the OpenCode inference gateway: the
 * resolved credential + selected model, fail closed when unconfigured.
 * Implemented by the settings side.
 */
public interface OpenCodeRuntimeSettingsPort {

    /** Resolved runtime settings, or {@link com.specagent.model.provider.OpenCodeModelException} NOT_CONFIGURED. */
    RuntimeOpenCodeSettings requireRuntimeSettings();
}
