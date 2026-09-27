package com.specagent.model.provider;

import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.provider.RoutingModelInferenceGateway;

import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.ModelProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 文件名:RoutingModelInferenceGateway.java
 *
 * 用途:权威的 {@link ModelInferenceGateway} 入口,也是唯一发生"按激活提供商
 * 路由"的地方。这里不做请求构建、不做 SSE 解析、不做降级(fallback)、不重试、
 * 没有任何 Agent 逻辑——只做:查激活提供商 -> 查注册表 -> 委派。
 */
@Component
@Primary
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class RoutingModelInferenceGateway implements ModelInferenceGateway {

    private final ActiveProviderPort providerSettings;
    private final OpenCodeModelInferenceGateway openCode;
    private final OpenRouterInferenceGateway openRouter;
    private final CustomInferenceGateway custom;

    public RoutingModelInferenceGateway(ActiveProviderPort providerSettings,
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
