package com.specagent.settings.provider;

import com.specagent.model.provider.ModelProvider;
import org.springframework.stereotype.Service;

/** Singleton active-provider persistence. Defaults to OPENCODE_ZEN for upgrades. */
@Service
public class ModelProviderSettingsService {

    private final ModelProviderSettingsRepository repository;

    public ModelProviderSettingsService(ModelProviderSettingsRepository repository) {
        this.repository = repository;
    }

    public ModelProvider activeProvider() {
        return repository.find()
                .map(s -> ModelProvider.fromCode(s.activeProvider()))
                .orElse(ModelProvider.OPENCODE_ZEN);
    }

    public String activeProviderCode() {
        return activeProvider().name();
    }

    public boolean isActiveCode(String code) {
        return activeProviderCode().equals(code);
    }

    public void setActiveProvider(ModelProvider provider) {
        repository.setActiveProvider(provider.name());
    }

    /** API-boundary helper so controllers never depend on model packages. */
    public void setActiveProviderByCode(String code) {
        repository.setActiveProvider(ModelProvider.fromCode(code).name());
    }
}
