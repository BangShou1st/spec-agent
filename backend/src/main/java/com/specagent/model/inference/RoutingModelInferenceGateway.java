package com.specagent.model.inference;

import com.specagent.model.provider.FragmentListener;
import com.specagent.model.provider.ModelProvider;
import com.specagent.settings.provider.ModelProviderSettingsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Authoritative {@link ModelInferenceGateway} entry. The ONLY place where
 * active-provider routing happens. No request building, no SSE parsing,
 * no fallback, no retry, no Agent logic here — just active provider to
 * registry to delegate.
 */
@Component
@Primary
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class RoutingModelInferenceGateway implements ModelInferenceGateway {

    private final ModelProviderSettingsService providerSettings;
    private final OpenCodeModelInferenceGateway openCode;
    private final OpenRouterInferenceGateway openRouter;
    private final CustomInferenceGateway custom;

    public RoutingModelInferenceGateway(ModelProviderSettingsService providerSettings,
                                        OpenCodeModelInferenceGateway openCode,
                                        OpenRouterInferenceGateway openRouter,
                                        CustomInferenceGateway custom) {
        this.providerSettings = providerSettings;
        this.openCode = openCode;
        this.openRouter = openRouter;
        this.custom = custom;
    }

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        return delegate(request).complete(request);
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request, FragmentListener listener) {
        return delegate(request).completeStreaming(request, listener);
    }

    private ModelInferenceGateway delegate(ModelInferenceRequest request) {
        ModelProvider active = providerSettings.activeProvider();
        return switch (active) {
            case OPENCODE_ZEN -> openCode;
            case OPENROUTER -> openRouter;
            case CUSTOM -> custom;
        };
    }
}
