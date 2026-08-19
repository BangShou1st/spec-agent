package com.specagent.settings.opencode;

import java.util.Optional;

public interface OpenCodeSettingsRepository {

    Optional<OpenCodeSettings> find();

    void upsert(OpenCodeSettings settings);
}
