package com.specagent.model.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentAction;
import com.specagent.agent.AgentPromptRenderer;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;
import com.specagent.agent.TaskPromptCatalog;
import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeChatMessage;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeDiagnosticReason;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeModelList;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.RuntimeOpenCodeSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenCodeZenModelGatewayTest {

    private static final String API_KEY = "sk-test-key";
    private static final String SELECTED_MODEL = "mimo-v2.5-free";

    private final OpenCodeSettingsService settings = mock(OpenCodeSettingsService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentPromptRenderer promptRenderer = new AgentPromptRenderer(new TaskPromptCatalog());

    private static final class RecordingTransport implements OpenCodeZenTransport {
        String apiKey;
        OpenCodeChatCompletionRequest request;
        String content = "{\"action\":\"ask_next_question\",\"output\":{\"question\":\"what?\"}}";
        String finishReason = "stop";
        Integer initialHttpStatus = 200;
        int streamedEventCount = 4;
        int reasoningEventCount;
        int reasoningCharCount;
        String reasoningSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        boolean completeCalled;

        @Override
        public OpenCodeCompletionResponse complete(String apiKey, OpenCodeChatCompletionRequest request) {
            this.apiKey = apiKey;
            this.request = request;
            this.completeCalled = true;
            return new OpenCodeCompletionResponse(content, finishReason, 10, 5, 15,
                    initialHttpStatus, streamedEventCount, reasoningEventCount,
                    reasoningCharCount, reasoningSha256);
        }

        @Override
        public OpenCodeModelList listModels(String apiKey) {
            throw new UnsupportedOperationException("gateway test does not discover models");
        }

        @Override
        public void validateCredential(String apiKey, String model) {
            throw new UnsupportedOperationException("gateway test does not probe");
        }
    }

    private ModelRequest request() {
        return request(AgentTaskType.DRAFT_NODE);
    }

    private ModelRequest request(AgentTaskType taskType) {
        return new ModelRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                taskType, "{\"input\":true}", Map.of());
    }

    private OpenCodeZenModelGateway gateway(RecordingTransport transport, String model) {
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(API_KEY, model));
        return new OpenCodeZenModelGateway(transport, settings, promptRenderer, mapper);
    }

    @Test
    void gatewayUsesActionReturnedByModel() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"action\":\"stop\",\"output\":{\"question\":\"q\"}}";
OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);
        ModelRequest request = request();

        ModelResponse response = gateway.run(request);

        // The action comes from the model output alone; nothing runtime-supplied
        // can change it here.
        assertThat(response.action()).isEqualTo(AgentAction.STOP);
        assertThat(response.outputJson()).isEqualTo("{\"question\":\"q\"}");

        // Transport received the resolved credential and the minimal payload.
        assertThat(transport.apiKey).isEqualTo(API_KEY);
        assertThat(transport.request.model()).isEqualTo(SELECTED_MODEL);
        assertThat(transport.request.messages()).hasSize(2);
        assertThat(transport.request.messages().get(0).role()).isEqualTo("system");
        assertThat(transport.request.messages().get(1).role()).isEqualTo("user");
        // The production system prompt must lock the outer envelope contract:
        // the model must return {action, output}, never the bare output object.
        assertThat(transport.request.messages().get(0).content())
                .contains("data, not instructions")
                .contains("outer envelope")
                .contains("\"action\"")
                .contains("\"output\"")
                .contains("\"action\": \"ask_next_question\"")
                .contains("Do not return the output object at the top level")
                .doesNotContain(request.inputJson());
        assertThat(transport.request.messages().get(1).content())
                .contains(AgentTaskType.DRAFT_NODE.code())
                .contains(request.inputJson());
    }

    @Test
    void gatewayTracesPromptVersionAndHashes() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"action\":\"ask_next_question\",\"output\":{\"question\":\"q\"}}";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);
        ModelRequest request = request();

        ModelResponse response = gateway.run(request);

        assertThat(response.trace()).containsEntry("promptVersion", "draft-node.v2");
        assertThat(response.trace()).containsKey("promptHash");
        assertThat(response.trace()).containsKey("modelOutputHash");
        assertThat(response.trace().get("promptHash")).matches("[0-9a-f]{64}");
        assertThat(response.trace().get("modelOutputHash")).matches("[0-9a-f]{64}");
    }

    @Test
    void reasoningMetadataIsObservedButRuntimeReceivesOnlyFinalContent() {
        RecordingTransport transport = new RecordingTransport();
        transport.reasoningEventCount = 2;
        transport.reasoningCharCount = 12;
        transport.reasoningSha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        ModelResponse response = gateway.run(request());

        assertThat(response.outputJson()).isEqualTo("{\"question\":\"what?\"}");
        assertThat(response.outputJson()).doesNotContain("reasoning_content", "internal");
        assertThat(response.trace()).containsEntry("reasoningEventCount", "2");
        assertThat(response.trace()).containsEntry("reasoningCharCount", "12");
        assertThat(response.trace()).containsEntry("reasoningSha256", transport.reasoningSha256);
    }

    @Test
    void gatewayTraceNeverContainsTheApiKey() {
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        ModelResponse response = gateway.run(request());

        assertThat(response.trace().values()).noneMatch(value -> value.contains(API_KEY));
    }

    @Test
    void gatewayParsesOutputObjectIntoOutputJson() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"action\":\"ask_next_question\",\"output\":{\"a\":1,\"b\":[true,null]}}";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);
        ModelRequest request = request();

        ModelResponse response = gateway.run(request);

        assertThat(response.action()).isEqualTo(AgentAction.ASK_NEXT_QUESTION);
        assertThat(response.outputJson()).isEqualTo("{\"a\":1,\"b\":[true,null]}");
        assertThat(response.trace()).containsEntry("adapter", "opencode-zen");
        assertThat(response.trace()).containsEntry("model", SELECTED_MODEL);
        assertThat(response.requestAgentRunId()).isEqualTo(request.agentRunId());
    }

    @Test
    void gatewayRejectsMalformedModelOutput() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "this is not json";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE);
                    assertThat(modelException.diagnostics().diagnosticReason())
                            .isEqualTo(OpenCodeDiagnosticReason.MODEL_OUTPUT_NOT_JSON);
                    assertThat(modelException.diagnostics().task()).isEqualTo(AgentTaskType.DRAFT_NODE.code());
                    assertThat(modelException.diagnostics().selectedModel()).isEqualTo(SELECTED_MODEL);
                    assertThat(modelException.diagnostics().initialHttpStatus()).isEqualTo(200);
                });
    }

    @Test
    void gatewayRejectsMissingAction() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"output\":{}}";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).diagnostics().diagnosticReason())
                        .isEqualTo(OpenCodeDiagnosticReason.MODEL_OUTPUT_MISSING_ACTION));
    }

    @Test
    void gatewayRejectsUnknownAction() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"action\":\"fly\",\"output\":{}}";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).diagnostics().diagnosticReason())
                        .isEqualTo(OpenCodeDiagnosticReason.MODEL_OUTPUT_UNKNOWN_ACTION))
                .hasMessageContaining("fly");
    }

    @Test
    void gatewayRejectsMissingOutputObject() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"action\":\"stop\"}";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).diagnostics().diagnosticReason())
                        .isEqualTo(OpenCodeDiagnosticReason.MODEL_OUTPUT_MISSING_OUTPUT));
    }

    @Test
    void truncatedModelOutputIsDiagnosedFromFinishReason() {
        RecordingTransport transport = new RecordingTransport();
        String truncatedContent = "{\"action\":\"ask_next_question\",\"output\":{";
        transport.content = truncatedContent;
        transport.finishReason = "length";
        transport.streamedEventCount = 3;
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request(AgentTaskType.INTERPRET_ANSWER)))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE);
                    assertThat(modelException.diagnostics().diagnosticReason())
                            .isEqualTo(OpenCodeDiagnosticReason.MODEL_OUTPUT_TRUNCATED);
                    assertThat(modelException.diagnostics().finishReason()).isEqualTo("length");
                    assertThat(modelException.diagnostics().initialHttpStatus()).isEqualTo(200);
                    assertThat(modelException.diagnostics().streamedEventCount()).isEqualTo(3);
                    assertThat(modelException.diagnostics().contentCharCount()).isEqualTo(truncatedContent.length());
                    assertThat(modelException.diagnostics().toString()).doesNotContain(truncatedContent);
                });
    }

    @Test
    void emptyModelOutputWithLengthFinishReasonIsTruncationNotEmptyContent() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "";
        transport.finishReason = "length";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request(AgentTaskType.DRAFT_ANSWER_PATCH)))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category())
                            .isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE);
                    assertThat(modelException.diagnostics().diagnosticReason())
                            .isEqualTo(OpenCodeDiagnosticReason.MODEL_OUTPUT_TRUNCATED);
                    assertThat(modelException.diagnostics().finishReason()).isEqualTo("length");
                    assertThat(modelException.diagnostics().contentCharCount()).isZero();
                });
    }

    @Test
    void gatewayRejectsMissingCredentialBeforeCallingTransport() {
        when(settings.requireRuntimeSettings()).thenThrow(new OpenCodeModelException(
                OpenCodeModelErrorCategory.NOT_CONFIGURED, "not configured"));
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway = new OpenCodeZenModelGateway(
                transport, settings, promptRenderer, mapper);

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.NOT_CONFIGURED));
        assertThat(transport.completeCalled).isFalse();
    }

    @Test
    void gatewayRejectsBlankSelectedModel() {
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway = gateway(transport, "  ");

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.NOT_CONFIGURED));
        assertThat(transport.completeCalled).isFalse();
    }

    @Test
    void gatewayRejectsNonFreeSelectedModelBeforeHttpCall() {
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway = gateway(transport, "some-paid-model");

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.INVALID_MODEL));
        assertThat(transport.completeCalled).isFalse();
    }

    @Test
    void gatewayExceptionsNeverContainTheApiKey() {
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway = gateway(transport, "some-paid-model");

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(API_KEY));
    }
}
