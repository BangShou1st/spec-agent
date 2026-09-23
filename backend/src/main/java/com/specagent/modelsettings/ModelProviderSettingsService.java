package com.specagent.modelsettings;

import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.ModelProvider;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Singleton active-provider persistence. Defaults to OPENCODE_ZEN for upgrades.
 *
 * <p>The active target is tracked twice on purpose: the preset code keeps the
 * original routing behaviour intact, and the row id is what makes several
 * user-defined providers distinguishable. A preset activated through the
 * legacy code-only path simply leaves the row id null.
 */
@Service
public class ModelProviderSettingsService implements ActiveProviderPort {

    private final ModelProviderSettingsRepository repository;

    public ModelProviderSettingsService(ModelProviderSettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    public ModelProvider activeProvider() {
        return repository.find()
                .map(s -> ModelProvider.fromCode(s.activeProvider()))
                .orElse(ModelProvider.OPENCODE_ZEN);
    }

    public String activeProviderCode() {
        return activeProvider().name();
    }

    /** Exact active row, or null when a preset was activated by code only. */
    public UUID activeProviderId() {
        return repository.find().map(ModelProviderSettings::activeProviderId).orElse(null);
    }

    public boolean isActiveCode(String code) {
        return activeProviderCode().equals(code);
    }

    public boolean isActiveId(UUID id) {
        return id != null && id.equals(activeProviderId());
    }

    public void setActiveProvider(ModelProvider provider) {
        repository.setActive(provider.name(), null);
    }

    /** API-boundary helper so controllers never depend on model packages. */
    public void setActiveProviderByCode(String code) {
        repository.setActive(ModelProvider.fromCode(code).name(), null);
    }

    /** Activates an exact row; the preset code is derived from the row itself. */
    public void setActiveTarget(String code, UUID activeProviderId) {
        repository.setActive(ModelProvider.fromCode(code).name(), activeProviderId);
    }
}
