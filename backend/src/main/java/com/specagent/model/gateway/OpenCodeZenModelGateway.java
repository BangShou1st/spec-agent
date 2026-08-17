package com.specagent.model.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentAction;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;
import com.specagent.credential.OpenCodeCredentialService;
import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeChatMessage;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Real-model {@link ModelGateway} backed by OpenCode Zen chat completions.
 *
 * <p>The gateway is provider-bound but domain-neutral: it resolves the stored
 * OpenCode credential, asks the model for a proposal, and parses the model's
 * own minimal envelope {@code {"action": "...", "output": {...}}} into a
 * {@link ModelResponse}. The action always comes from the model output — the
 * gateway never copies a runtime expectation into {@code ModelResponse.action}.
 * The runtime owns correlation validation, expected-action validation,
 * reflection gates and persistence.
 *
 * <p>Only OpenCode free models are allowed: a non-free selected model is
 * rejected before any HTTP completion call. The selected model is a runtime
 * setting passed explicitly ({@code spec.agent.model.opencode.model}); the
 * gateway never picks a model on its own, and the probe/discovery model list
 * stays dynamic.
 *
 * <p>The bean is only registered when {@code spec.agent.model.gateway=opencode};
 * the deterministic fake remains the ModelGateway for automated tests.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.model.gateway", havingValue = "opencode")
public class OpenCodeZenModelGateway implements ModelGateway {

    private static final int MAX_TOKENS = 4096;
    private static final double TEMPERATURE = 0.0;
    private static final String SYSTEM_PROMPT =
            "You are a requirement specification agent. Respond with valid JSON only, in the form "
                    + "{\"action\": \"...\", \"output\": {...}}. The action must be one of: "
                    + "ask_next_question, interpret_answer, request_confirmation, explain_conflict, "
                    + "suggest_branch, generate_spec, stop.";

    private final OpenCodeZenTransport transport;
    private final OpenCodeCredentialService credentialService;
    private final ObjectMapper mapper;
    private final String selectedModel;

    public OpenCodeZenModelGateway(OpenCodeZenTransport transport,
                                   OpenCodeCredentialService credentialService,
                                   ObjectMapper mapper,
                                   @Value("${spec.agent.model.opencode.model:}") String selectedModel) {
        this.transport = transport;
        this.credentialService = credentialService;
        this.mapper = mapper;
        this.selectedModel = selectedModel == null ? "" : selectedModel.trim();
    }

    @Override
    public ModelResponse run(ModelRequest request) {
        String apiKey = credentialService.resolveOpenCode().orElseThrow(
                () -> new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                        "OpenCode credential is not configured"));
        if (selectedModel.isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "No OpenCode model selected (spec.agent.model.opencode.model)");
        }
        if (!selectedModel.endsWith("-free")) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode gateway requires a free model; configured model is not free: " + selectedModel);
        }

        String userPrompt = "Task: " + request.taskType().code()
                + "\nContext snapshot: " + request.contextSnapshotId()
                + "\nRequest payload:\n" + request.inputJson();

        OpenCodeCompletionResponse completion = transport.complete(apiKey,
                new OpenCodeChatCompletionRequest(
                        selectedModel,
                        List.of(new OpenCodeChatMessage("system", SYSTEM_PROMPT),
                                new OpenCodeChatMessage("user", userPrompt)),
                        TEMPERATURE,
                        MAX_TOKENS));

        return toModelResponse(request, completion.content());
    }

    /**
     * Parses the model's minimal envelope and maps it onto a {@link ModelResponse}.
     * The action is the model's own proposal, never a runtime-supplied value;
     * correlation fields wrap this synchronous request so the runtime can verify
     * which request this response belongs to.
     */
    private ModelResponse toModelResponse(ModelRequest request, String content) {
        Envelope envelope = parseEnvelope(content);
        AgentAction action;
        try {
            action = AgentAction.fromCode(envelope.action());
        } catch (IllegalArgumentException ex) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode model returned an unknown action: " + envelope.action(), ex);
        }
        String outputJson = writeOutput(envelope.output());
        return new ModelResponse(
                request.agentRunId(),
                request.contextSnapshotId(),
                request.taskType(),
                action,
                outputJson,
                Map.of("adapter", "opencode-zen", "model", selectedModel,
                        "task", request.taskType().code()));
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