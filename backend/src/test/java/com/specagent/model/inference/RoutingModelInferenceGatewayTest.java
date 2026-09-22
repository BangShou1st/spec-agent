package com.specagent.model.inference;

import com.specagent.model.provider.FragmentListener;
import com.specagent.model.provider.ModelProvider;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Active-provider routing through the model-owned {@link ActiveProviderPort}
 * boundary: exactly one delegate per request, no cross-provider fallback,
 * no retry — the historical RoutingModelInferenceGateway semantics.
 */
@ExtendWith(MockitoExtension.class)
class RoutingModelInferenceGatewayTest {

    @Mock
    private ActiveProviderPort activeProvider;
    @Mock
    private OpenCodeModelInferenceGateway openCode;
    @Mock
    private OpenRouterInferenceGateway openRouter;
    @Mock
    private CustomInferenceGateway custom;

    private RoutingModelInferenceGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new RoutingModelInferenceGateway(activeProvider, openCode, openRouter, custom);
    }

    private static ModelInferenceRequest request() {
        return new ModelInferenceRequest(UUID.randomUUID(), "DECISION",
                List.of(new ModelInferenceMessage("user", "{}")), null);
    }

    @Test
    void openCodeZenRoutesOnlyToTheOpenCodeDelegate() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENCODE_ZEN);
        ModelInferenceRequest request = request();

        gateway.complete(request);

        verify(openCode).complete(request);
        verifyNoInteractions(openRouter, custom);
    }

    @Test
    void openRouterRoutesOnlyToTheOpenRouterDelegate() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENROUTER);
        ModelInferenceRequest request = request();

        gateway.complete(request);

        verify(openRouter).complete(request);
        verifyNoInteractions(openCode, custom);
    }

    @Test
    void customRoutesOnlyToTheCustomDelegate() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.CUSTOM);
        ModelInferenceRequest request = request();

        gateway.complete(request);

        verify(custom).complete(request);
        verifyNoInteractions(openCode, openRouter);
    }

    @Test
    void streamingFollowsTheSameActiveProviderRouting() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENROUTER);
        ModelInferenceRequest request = request();
        FragmentListener listener = mock(FragmentListener.class);

        gateway.completeStreaming(request, listener);

        verify(openRouter).completeStreaming(request, listener);
        verifyNoInteractions(openCode, custom);
    }

    @Test
    void delegateFailurePropagatesWithoutFallbackOrRetry() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENCODE_ZEN);
        OpenCodeModelException boom = new OpenCodeModelException(
                OpenCodeModelErrorCategory.NOT_CONFIGURED, "provider is not configured");
        doThrow(boom).when(openCode).complete(any());
        ModelInferenceRequest request = request();

        assertThatThrownBy(() -> gateway.complete(request)).isSameAs(boom);

        verify(activeProvider, times(1)).activeProvider();
        verify(openCode, times(1)).complete(request);
        verifyNoInteractions(openRouter, custom);
    }
}
