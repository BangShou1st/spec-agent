package com.specagent.model.inference;

import com.specagent.model.provider.ModelProvider;

/**
 * Narrow runtime-settings seam for the routing gateway: which provider is
 * active right now. Owned by the model inference surface and implemented by
 * the settings side, so inference never imports {@code com.specagent.settings}.
 */
public interface ActiveProviderPort {

    /** The active provider; defaults to OPENCODE_ZEN when none is stored. */
    ModelProvider activeProvider();
}
