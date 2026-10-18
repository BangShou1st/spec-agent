package com.specagent.model.provider;

import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.GaNativeInferenceGateway;
import com.specagent.model.contract.ModelProvider;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * 文件名:RoutingGaNativeInferenceGateway.java
 *
 * 用途:GA 原生模型执行的唯一提供商路由点。它按<b>已冻结绑定</b>里的提供商把请求交给
 * 对应的原生传输,不做请求构建、不做 SSE 解析、不做降级、不重试、不切换提供商或模型。
 *
 * <p>未注册原生传输的提供商(目前 OpenRouter / Custom)一律以
 * {@link UnsupportedOperationException} 失败关闭——绝不退回文本模拟工具调用,也绝不
 * 悄悄改用 OpenCode Zen。
 */
@Component
public class RoutingGaNativeInferenceGateway implements GaNativeInferenceGateway {

    private final OpenCodeZenTransport openCodeZen;
    private final KiloGaNativeTransport kilo;

    public RoutingGaNativeInferenceGateway(OpenCodeZenTransport openCodeZen, KiloGaNativeTransport kilo) {
        this.openCodeZen = openCodeZen;
        this.kilo = kilo;
    }

    @Override
    public GaModelContract.Response complete(GaModelTarget target, UUID conversationId,
                                             GaModelContract.Request request, Duration remainingBudget,
                                             BooleanSupplier active) {
        return switch (providerOf(target)) {
            // 非流式入口保持各提供商已验证的原生调用形态(绝不把 complete 伪装成 stream)。
            case OPENCODE_ZEN -> openCodeZen.completeNativeGa(target.credential(),
                    OpenCodeZenSessionIds.forConversation(conversationId), request, target.model(),
                    remainingBudget, active);
            case KILO -> kilo.complete(target, request, remainingBudget, active);
            case OPENROUTER, CUSTOM -> throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
        };
    }

    @Override
    public GaModelContract.Response stream(GaModelTarget target, UUID conversationId,
                                           GaModelContract.Request request, Duration remainingBudget,
                                           BooleanSupplier active, FragmentListener listener) {
        return switch (providerOf(target)) {
            case OPENCODE_ZEN -> openCodeZen.streamNativeGa(target.credential(),
                    OpenCodeZenSessionIds.forConversation(conversationId), request, target.model(),
                    remainingBudget, active, listener);
            case KILO -> kilo.stream(target, request, remainingBudget, active, listener);
            case OPENROUTER, CUSTOM -> throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
        };
    }

    private static ModelProvider providerOf(GaModelTarget target) {
        try {
            return ModelProvider.fromCode(target.provider());
        } catch (IllegalArgumentException unknownProvider) {
            throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
        }
    }
}
