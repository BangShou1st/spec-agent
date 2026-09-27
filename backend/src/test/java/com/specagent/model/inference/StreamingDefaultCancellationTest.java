package com.specagent.model.inference;

import com.specagent.model.contract.ModelOutputContract;

import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.inference.StreamingDefaultCancellationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.specagent.model.contract.FragmentListener;
import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeChatMessage;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.model.contract.StreamCancelledException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
/**
 * 文件名:StreamingDefaultCancellationTest.java
 *
 * 测试目标:验证流式接口的默认实现遵守"监听器拒绝片段即取消"的契约:
 * ModelInferenceGateway 与 OpenCodeZenTransport 两个层级的 completeStreaming,
 * 当监听器拒绝片段时抛出 StreamCancelledException,接受片段时正常返回内容。
 */
class StreamingDefaultCancellationTest {
    private static final FragmentListener ACCEPT = fragment -> true;
    private static final FragmentListener DECLINE = fragment -> false;
    @Test void gatewayDefaultThrowsWhenListenerDeclines() {
        ModelInferenceGateway gateway = request ->
                new ModelInferenceResponse("hello", "stop", 0, 0);
        ModelInferenceRequest request = new ModelInferenceRequest(
                UUID.randomUUID(), "test", List.of(), 64, ModelOutputContract.text());
        assertThatThrownBy(() -> gateway.completeStreaming(request, DECLINE))
                .isInstanceOf(StreamCancelledException.class);
        assertThat(gateway.completeStreaming(request, ACCEPT).content()).isEqualTo("hello");
    }
    @Test void transportDefaultThrowsWhenListenerDeclines() {
        OpenCodeZenTransport transport = new OpenCodeZenTransport() {
            @Override
            public OpenCodeCompletionResponse complete(String apiKey, String sessionId,
                    OpenCodeChatCompletionRequest request) {
                return new OpenCodeCompletionResponse("world", "stop", 0, 0, 0);
            }
            @Override
            public com.specagent.model.provider.OpenCodeModelList listModels(String apiKey) {
                throw new UnsupportedOperationException();
            }
            @Override
            public void validateCredential(String apiKey, String model) {
                throw new UnsupportedOperationException();
            }
        };
        OpenCodeChatCompletionRequest request = new OpenCodeChatCompletionRequest(
                "m", List.of(new OpenCodeChatMessage("user", "hi")));
        assertThatThrownBy(() -> transport.completeStreaming("k", "s", request, DECLINE))
                .isInstanceOf(StreamCancelledException.class);
        assertThat(transport.completeStreaming("k", "s", request, ACCEPT).content()).isEqualTo("world");
    }
}
