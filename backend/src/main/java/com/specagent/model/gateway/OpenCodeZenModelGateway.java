package com.specagent.model.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentAction;
import com.specagent.agent.AgentPromptRenderer;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;
import com.specagent.common.Hashes;
import com.specagent.model.contract.ModelPrompt;
import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeChatMessage;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeDiagnosticReason;
import com.specagent.model.provider.OpenCodeFailureDiagnostics;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.RuntimeOpenCodeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(OpenCodeZenModelGateway.class);
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
        String selectedModel = "not provided";
        try {
            RuntimeOpenCodeSettings settings = settingsService.requireRuntimeSettings();
            String apiKey = settings.apiKey();
            selectedModel = settings.selectedModel();
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
                            TEMPERATURE));

            return toModelResponse(request, completion, prompt, selectedModel);
        } catch (OpenCodeModelException ex) {
            OpenCodeModelException enriched = enrichDiagnostics(ex, request, selectedModel);
            logFailure(enriched);
            throw enriched;
        }
    }

    /**
     * Parses the model's minimal envelope and maps it onto a {@link ModelResponse}.
     * The action is the model's own proposal, never a runtime-supplied value;
     * correlation fields wrap this synchronous request so the runtime can verify
     * which request this response belongs to. The trace carries the rendered
     * prompt version and content hashes so output can be attributed; it never
     * carries the API key.
     */
    private ModelResponse toModelResponse(ModelRequest request,
                                          OpenCodeCompletionResponse completion,
                                          ModelPrompt prompt,
                                          String selectedModel) {
        if ("length".equalsIgnoreCase(completion.finishReason())) {
            throw modelOutputFailure(OpenCodeDiagnosticReason.MODEL_OUTPUT_TRUNCATED,
                    "OpenCode model output was truncated", completion, null);
        }
        Envelope envelope = parseEnvelope(completion);
        AgentAction action;
        try {
            action = AgentAction.fromCode(envelope.action());
        } catch (IllegalArgumentException ex) {
            throw modelOutputFailure(OpenCodeDiagnosticReason.MODEL_OUTPUT_UNKNOWN_ACTION,
                    "OpenCode model returned an unknown action: " + envelope.action(), completion, ex);
        }
        String outputJson = writeOutput(envelope.output());
        Map<String, String> trace = new LinkedHashMap<>();
        trace.put("adapter", "opencode-zen");
        trace.put("model", selectedModel);
        trace.put("task", request.taskType().code());
        trace.put("promptVersion", prompt.version());
        trace.put("promptHash", Hashes.sha256Hex(prompt.systemPrompt() + "\n" + prompt.userPrompt()));
        trace.put("modelOutputHash", Hashes.sha256Hex(completion.content()));
        trace.put("reasoningEventCount", Integer.toString(completion.reasoningEventCount()));
        trace.put("reasoningCharCount", Integer.toString(completion.reasoningCharCount()));
        trace.put("reasoningSha256", completion.reasoningSha256());
        return new ModelResponse(
                request.agentRunId(),
                request.contextSnapshotId(),
                request.taskType(),
                action,
                outputJson,
                trace);
    }

    private Envelope parseEnvelope(OpenCodeCompletionResponse completion) {
        String content = completion.content();
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (IOException ex) {
            throw modelOutputFailure(OpenCodeDiagnosticReason.MODEL_OUTPUT_NOT_JSON,
                    "OpenCode model output is not valid JSON", completion, ex);
        }
        if (root == null || !root.isObject()) {
            throw modelOutputFailure(OpenCodeDiagnosticReason.MODEL_OUTPUT_NOT_JSON,
                    "OpenCode model output is not a JSON object", completion, null);
        }
        JsonNode action = root.get("action");
        if (action == null || !action.isTextual() || action.asText().isBlank()) {
            throw modelOutputFailure(OpenCodeDiagnosticReason.MODEL_OUTPUT_MISSING_ACTION,
                    "OpenCode model output has no action", completion, null);
        }
        JsonNode output = root.get("output");
        if (output == null || !output.isObject()) {
            throw modelOutputFailure(OpenCodeDiagnosticReason.MODEL_OUTPUT_MISSING_OUTPUT,
                    "OpenCode model output has no output object", completion, null);
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

    private OpenCodeModelException modelOutputFailure(OpenCodeDiagnosticReason reason,
                                                      String message,
                                                      OpenCodeCompletionResponse completion,
                                                      Throwable cause) {
        OpenCodeDiagnosticReason effectiveReason = "length".equalsIgnoreCase(completion.finishReason())
                ? OpenCodeDiagnosticReason.MODEL_OUTPUT_TRUNCATED : reason;
        String effectiveMessage = "length".equalsIgnoreCase(completion.finishReason())
                ? "OpenCode model output was truncated" : message;
        String content = completion.content();
        OpenCodeFailureDiagnostics diagnostics = new OpenCodeFailureDiagnostics(
                "not provided", "not provided", "/chat/completions", completion.initialHttpStatus(),
                effectiveReason, completion.finishReason(), completion.streamedEventCount(),
                content.length(), Hashes.sha256Hex(content), null, List.of(), null, List.of(),
                "not provided", "not provided", "not provided", "not provided", "not provided",
                "not provided", "not provided", "not provided",
                completion.reasoningEventCount(), completion.reasoningCharCount(),
                completion.reasoningSha256());
        return new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                effectiveMessage, completion.initialHttpStatus(), cause).withDiagnostics(diagnostics);
    }

    private OpenCodeModelException enrichDiagnostics(OpenCodeModelException exception,
                                                     ModelRequest request,
                                                     String selectedModel) {
        OpenCodeFailureDiagnostics diagnostics = exception.diagnostics()
                .withTask(request.taskType().code());
        if (selectedModel != null && !selectedModel.isBlank()) {
            diagnostics = diagnostics.withSelectedModel(selectedModel);
        }
        return exception.withDiagnostics(diagnostics);
    }

    private void logFailure(OpenCodeModelException exception) {
        OpenCodeFailureDiagnostics diagnostics = exception.diagnostics();
        LOG.warn("OpenCode provider diagnostics category={} task={} selectedModel={} endpointPath={} "
                        + "initialHttpStatus={} diagnosticReason={} finishReason={} streamedEventCount={} "
                        + "contentCharCount={} contentSha256={} eventIndex={} topLevelFields={} choicesCount={} "
                        + "deltaFields={} providerType={} providerCode={} providerMessage={} retryAfter={} "
                        + "xRequestId={} requestId={} cfRay={} traceId={} reasoningEventCount={} "
                        + "reasoningCharCount={} reasoningSha256={}",
                exception.category(), diagnostics.task(), diagnostics.selectedModel(), diagnostics.endpointPath(),
                diagnostics.initialHttpStatus(), diagnosticReason(diagnostics), diagnostics.finishReason(),
                diagnostics.streamedEventCount(), diagnostics.contentCharCount(), diagnostics.contentSha256(),
                diagnostics.eventIndex(), diagnostics.topLevelFields(), diagnostics.choicesCount(),
                diagnostics.deltaFields(), diagnostics.providerType(), diagnostics.providerCode(),
                diagnostics.providerMessage(), diagnostics.retryAfter(), diagnostics.xRequestId(),
                diagnostics.requestId(), diagnostics.cfRay(), diagnostics.traceId(),
                diagnostics.reasoningEventCount(), diagnostics.reasoningCharCount(),
                diagnostics.reasoningSha256());
    }

    private static String diagnosticReason(OpenCodeFailureDiagnostics diagnostics) {
        return diagnostics.diagnosticReason() == null
                ? "not provided" : diagnostics.diagnosticReason().name();
    }

    /**
     * Minimal provider wire DTO for the model envelope. The output node stays
     * raw JSON so each task's Phase 4 contract parses it unchanged.
     */
    private record Envelope(String action, JsonNode output) {
    }
}
