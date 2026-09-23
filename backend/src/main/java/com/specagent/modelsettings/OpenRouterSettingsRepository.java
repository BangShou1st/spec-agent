package com.specagent.modelsettings;

import java.util.Optional;

public interface OpenRouterSettingsRepository {
    Optional<OpenRouterSettings> find();
    void upsert(OpenRouterSettings settings);
    void markValidated(long configRevision);
}
