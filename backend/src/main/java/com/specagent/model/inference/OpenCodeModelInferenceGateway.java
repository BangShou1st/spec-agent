package com.specagent.model.inference;

import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeChatMessage;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.RuntimeOpenCodeSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Real {@link ModelInferenceGateway} backed by the frozen OpenCode Zen
 * transport. Credential resolution, free-model policy and HTTP transport stay
 * exactly where they were; this adapter only reshapes the call into the
 * neutral inference seam so the Python brain can reach the same proven
 * transport through the internal broker without ever seeing a key.
 *
 * <p>No retry, no provider fallback. Generation limits are not forwarded:
 * production OpenCode completions keep their verified request shape.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class OpenCodeModelInferenceGateway implements ModelInferenceGateway {

    private final OpenCodeZenTransport transport;
    private final OpenCodeSettingsService settingsService;

    public OpenCodeModelInferenceGateway(OpenCodeZenTransport transport,
                                         OpenCodeSettingsService settingsService) {
        this.transport = transport;
        this.settingsService = settingsService;
    }

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        RuntimeOpenCodeSettings settings = settingsService.requireRuntimeSettings();
        String selectedModel = settings.selectedModel();
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "No OpenCode model is selected");
        }
        if (!selectedModel.endsWith("-free")) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode gateway requires a free model; configured model is not free: "
                            + selectedModel);
        }
        List<OpenCodeChatMessage> messages = request.messages().stream()
                .map(message -> new OpenCodeChatMessage(message.role(), message.content()))
                .toList();
        OpenCodeCompletionResponse completion =
                transport.complete(settings.apiKey(), new OpenCodeChatCompletionRequest(
                        selectedModel, messages));
        return new ModelInferenceResponse(
                completion.content(),
                completion.finishReason(),
                completion.promptTokens(),
                completion.completionTokens());
    }
}
