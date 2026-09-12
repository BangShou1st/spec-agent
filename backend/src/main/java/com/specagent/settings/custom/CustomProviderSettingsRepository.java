package com.specagent.settings.custom;

import java.util.Optional;

public interface CustomProviderSettingsRepository {
    Optional<CustomProviderSettings> find();
    void upsert(CustomProviderSettings settings);
    void markValidated(long configRevision);
}
