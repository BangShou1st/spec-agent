package com.specagent.modelsettings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Row-per-provider storage. No preset is special-cased at this layer. */
public interface ModelProviderRepository {

    /** Stable page order: presets first, then user-defined rows by position. */
    List<ModelProviderRecord> findAll();

    Optional<ModelProviderRecord> findById(UUID id);

    /** First row of a preset, used by the legacy per-provider facades. */
    Optional<ModelProviderRecord> findFirstByPreset(String preset);

    void insert(ModelProviderRecord record);

    void update(ModelProviderRecord record);

    void delete(UUID id);

    /** Marks the tested revision so activation can gate on an exact match. */
    void markValidated(UUID id, long configRevision);
}
