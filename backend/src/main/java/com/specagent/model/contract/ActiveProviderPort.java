package com.specagent.model.contract;

import com.specagent.model.contract.ModelProvider;

/**
 * Narrow runtime-settings seam for the routing gateway: which provider is
 * active right now. Owned by the model inference surface and implemented by
 * the settings side, so inference never imports {@code com.specagent.modelsettings}.
 */
public interface ActiveProviderPort {

    /** The active provider; defaults to OPENCODE_ZEN when none is stored. */
    ModelProvider activeProvider();
}
