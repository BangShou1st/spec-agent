package com.specagent.model.inference;

import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeChatMessage;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenSessionIds;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.RuntimeOpenCodeSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real {@link ModelInferenceGateway} backed by the frozen OpenCode Zen
 * transport. Credential resolution, free-model policy and HTTP transport stay
 * exactly where they were; this adapter only reshapes the call into the
 * neutral inference seam so the Python brain can reach the same proven
 * transport through the internal broker without ever seeing a key. Product
 * database settings remain free-only; the explicitly isolated external eval
 * source may select any exact qualified provider model.
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
        boolean externalEvaluation = "external-environment:SPEC_AGENT_EVAL_OPENCODE_KEY"
                .equals(settings.credentialSource());
        if (!externalEvaluation && !selectedModel.endsWith("-free")) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode gateway requires a free model; configured model is not free: "
                            + selectedModel);
        }
        List<OpenCodeChatMessage> messages = request.messages().stream()
                .map(message -> new OpenCodeChatMessage(message.role(), message.content()))
                .toList();
        OpenCodeChatCompletionRequest completionRequest =
                toProviderRequest(selectedModel, messages, request.outputContract());
        OpenCodeCompletionResponse completion =
                transport.complete(settings.apiKey(),
                        OpenCodeZenSessionIds.forRun(request.runId()),
                        completionRequest);
        return toResponse(completion);
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request,
            com.specagent.model.provider.FragmentListener listener) {
        RuntimeOpenCodeSettings settings = settingsService.requireRuntimeSettings();
        String selectedModel = settings.selectedModel();
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "No OpenCode model is selected");
        }
        boolean externalEvaluation = "external-environment:SPEC_AGENT_EVAL_OPENCODE_KEY"
                .equals(settings.credentialSource());
        if (!externalEvaluation && !selectedModel.endsWith("-free")) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode gateway requires a free model; configured model is not free: "
                            + selectedModel);
        }
        List<OpenCodeChatMessage> messages = request.messages().stream()
                .map(message -> new OpenCodeChatMessage(message.role(), message.content()))
                .toList();
        OpenCodeChatCompletionRequest completionRequest =
                toProviderRequest(selectedModel, messages, request.outputContract());
        OpenCodeCompletionResponse completion =
                transport.completeStreaming(settings.apiKey(),
                        OpenCodeZenSessionIds.forRun(request.runId()),
                        completionRequest, listener);
        return toResponse(completion);
    }

    private static ModelInferenceResponse toResponse(OpenCodeCompletionResponse completion) {
        return new ModelInferenceResponse(
                completion.content(),
                completion.finishReason(),
                completion.promptTokens(),
                completion.completionTokens());
    }

    /**
     * Translates the neutral output contract into the OpenCode-native
     * request shape. Text stays on the historical wire shape; a JSON object
     * becomes {@code response_format.type=json_object} without claiming
     * native schema enforcement; a JSON schema becomes
     * {@code response_format.json_schema} with strict enforcement.
     * Unknown contract variants fail closed instead of silently
     * downgrading to text. No silent fallback between modes.
     */
    private static OpenCodeChatCompletionRequest toProviderRequest(
            String selectedModel,
            List<OpenCodeChatMessage> messages,
            ModelOutputContract outputContract) {
        if (outputContract instanceof ModelOutputContract.JsonSchema jsonSchema) {
            Map<String, Object> schemaWrapper = new LinkedHashMap<>();
            schemaWrapper.put("name", jsonSchema.name());
            schemaWrapper.put("strict", true);
            schemaWrapper.put("schema", jsonSchema.schema());
            Map<String, Object> responseFormat = new LinkedHashMap<>();
            responseFormat.put("type", "json_schema");
            responseFormat.put("json_schema", schemaWrapper);
            return new OpenCodeChatCompletionRequest(selectedModel, messages, responseFormat);
        }
        if (outputContract instanceof ModelOutputContract.JsonObject) {
            Map<String, Object> responseFormat = new LinkedHashMap<>();
            responseFormat.put("type", "json_object");
            return new OpenCodeChatCompletionRequest(selectedModel, messages, responseFormat);
        }
        if (outputContract instanceof ModelOutputContract.Text) {
            return new OpenCodeChatCompletionRequest(selectedModel, messages);
        }
        throw new IllegalArgumentException(
                "Unsupported ModelOutputContract: " + outputContract);
    }
}
