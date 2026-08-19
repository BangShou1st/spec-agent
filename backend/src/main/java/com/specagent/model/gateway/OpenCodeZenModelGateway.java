package com.specagent.model.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentAction;
import com.specagent.agent.AgentPromptRenderer;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;
import com.specagent.common.Hashes;
import com.specagent.model.contract.ModelPrompt;
import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeChatMessage;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.RuntimeOpenCodeSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-model {@link ModelGateway} backed by OpenCode Zen chat completions.
 *
 * <p>The gateway is provider-bound but domain-neutral: it resolves the stored
 * OpenCode credential, renders the production prompt through
 * {@link AgentPromptRenderer}, asks the model for a proposal, and parses the
 * model's own minimal envelope {@code {"action": "...", "output": {...}}} into
 * a {@link ModelResponse}. The action always comes from the model output — the
 * gateway never copies a runtime expectation into {@code ModelResponse.action}.
 * The runtime owns correlation validation, expected-action validation,
 * task-specific structured parsing, reflection gates and persistence.
 *
 * <p>Only OpenCode free models are allowed: a non-free selected model is
 * rejected before any HTTP completion call. The selected model is a runtime
 * setting passed explicitly ({@code spec.agent.model.opencode.model}); the
 * gateway never picks a model on its own, and the probe/discovery model list
 * stays dynamic.
 *
 * <p>The bean is the normal product gateway. Deterministic scripted gateways
 * are selected only by the explicit test profile.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.model.gateway", havingValue = "opencode", matchIfMissing = true)
public class OpenCodeZenModelGateway implements ModelGateway {

    private static final int MAX_TOKENS = 4096;
    private static final double TEMPERATURE = 0.0;

    private final OpenCodeZenTransport transport;
    private final OpenCodeSettingsService settingsService;
    private final AgentPromptRenderer promptRenderer;
    private final ObjectMapper mapper;

    public OpenCodeZenModelGateway(OpenCodeZenTransport transport,
                                   OpenCodeSettingsService settingsService,
                                   AgentPromptRenderer promptRenderer,
                                   ObjectMapper mapper) {
        this.transport = transport;
        this.settingsService = settingsService;
        this.promptRenderer = promptRenderer;
        this.mapper = mapper;
    }

    @Override
    public ModelResponse run(ModelRequest request) {
        RuntimeOpenCodeSettings settings = settingsService.requireRuntimeSettings();
        String apiKey = settings.apiKey();
        String selectedModel = settings.selectedModel();
        if (selectedModel.isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "No OpenCode model is selected");
        }
        if (!selectedModel.endsWith("-free")) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode gateway requires a free model; configured model is not free: " + selectedModel);
        }

        ModelPrompt prompt = promptRenderer.render(request);

        OpenCodeCompletionResponse completion = transport.complete(apiKey,
                new OpenCodeChatCompletionRequest(
                        selectedModel,
                        List.of(new OpenCodeChatMessage("system", prompt.systemPrompt()),
                                new OpenCodeChatMessage("user", prompt.userPrompt())),
                        TEMPERATURE,
                        MAX_TOKENS));

        return toModelResponse(request, completion.content(), prompt, selectedModel);
    }

    /**
     * Parses the model's minimal envelope and maps it onto a {@link ModelResponse}.
     * The action is the model's own proposal, never a runtime-supplied value;
     * correlation fields wrap this synchronous request so the runtime can verify
     * which request this response belongs to. The trace carries the rendered
     * prompt version and content hashes so output can be attributed; it never
     * carries the API key.
     */
    private ModelResponse toModelResponse(ModelRequest request, String content, ModelPrompt prompt,
                                          String selectedModel) {
        Envelope envelope = parseEnvelope(content);
        AgentAction action;
        try {
            action = AgentAction.fromCode(envelope.action());
        } catch (IllegalArgumentException ex) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode model returned an unknown action: " + envelope.action(), ex);
        }
        String outputJson = writeOutput(envelope.output());
        Map<String, String> trace = new LinkedHashMap<>();
        trace.put("adapter", "opencode-zen");
        trace.put("model", selectedModel);
        trace.put("task", request.taskType().code());
        trace.put("promptVersion", prompt.version());
        trace.put("promptHash", Hashes.sha256Hex(prompt.systemPrompt() + "\n" + prompt.userPrompt()));
        trace.put("modelOutputHash", Hashes.sha256Hex(content));
        return new ModelResponse(
                request.agentRunId(),
                request.contextSnapshotId(),
                request.taskType(),
                action,
                outputJson,
                trace);
    }

    private Envelope parseEnvelope(String content) {
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (IOException ex) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode model output is not valid JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode model output is not a JSON object");
        }
        JsonNode action = root.get("action");
        if (action == null || !action.isTextual() || action.asText().isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode model output has no action");
        }
        JsonNode output = root.get("output");
        if (output == null || !output.isObject()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode model output has no output object");
        }
        return new Envelope(action.asText(), output);
    }

    private String writeOutput(JsonNode output) {
        try {
            return mapper.writeValueAsString(output);
        } catch (IOException ex) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode model output could not be serialized", ex);
        }
    }

    /**
     * Minimal provider wire DTO for the model envelope. The output node stays
     * raw JSON so each task's Phase 4 contract parses it unchanged.
     */
    private record Envelope(String action, JsonNode output) {
    }
}
