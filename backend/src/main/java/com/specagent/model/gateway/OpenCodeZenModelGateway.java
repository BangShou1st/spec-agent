package com.specagent.model.gateway;

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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Real-model {@link ModelGateway} backed by OpenCode Zen chat completions.
 *
 * <p>The gateway is provider-bound but domain-neutral: it resolves the stored
 * OpenCode credential, maps a {@link ModelRequest} onto the minimal chat
 * completion envelope, and maps the parsed completion back into a
 * {@link ModelResponse}. It never validates semantics itself — the runtime
 * owns correlation validation, expected-action validation, reflection gates
 * and persistence.
 *
 * <p>The selected model is a runtime setting passed explicitly
 * ({@code spec.agent.model.opencode.model}); the gateway never picks a model
 * on its own. Phase 5.1 does not build model-selection persistence.
 */
@Component
public class OpenCodeZenModelGateway implements ModelGateway {

    private static final int MAX_TOKENS = 4096;
    private static final double TEMPERATURE = 0.0;
    private static final String SYSTEM_PROMPT =
            "You are a requirement specification agent. Respond with valid JSON only.";

    private final OpenCodeZenTransport transport;
    private final OpenCodeCredentialService credentialService;
    private final String selectedModel;

    public OpenCodeZenModelGateway(OpenCodeZenTransport transport,
                                   OpenCodeCredentialService credentialService,
                                   @Value("${spec.agent.model.opencode.model:}") String selectedModel) {
        this.transport = transport;
        this.credentialService = credentialService;
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

        String expectedActionCode = request.metadata().get(ModelRequest.METADATA_EXPECTED_ACTION);
        if (expectedActionCode == null || expectedActionCode.isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "Model request has no expectedAction metadata");
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

        return new ModelResponse(
                request.agentRunId(),
                request.contextSnapshotId(),
                request.taskType(),
                AgentAction.fromCode(expectedActionCode),
                completion.content(),
                Map.of("adapter", "opencode-zen", "model", selectedModel,
                        "task", request.taskType().code()));
    }
}