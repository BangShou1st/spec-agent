package com.specagent.settings.provider;

import java.util.Optional;

public interface ModelProviderSettingsRepository {
    Optional<ModelProviderSettings> find();
    void setActiveProvider(String activeProvider);
}
