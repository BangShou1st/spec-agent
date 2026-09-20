package com.specagent.settings.provider;

import java.util.Optional;
import java.util.UUID;

public interface ModelProviderSettingsRepository {
    Optional<ModelProviderSettings> find();

    /**
     * Persists the active target. {@code activeProviderId} may be null for a
     * preset activated through the legacy code-only path.
     */
    void setActive(String activeProvider, UUID activeProviderId);
}
