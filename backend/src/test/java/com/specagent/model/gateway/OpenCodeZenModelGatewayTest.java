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
        boolean completeCalled;

        @Override
        public OpenCodeCompletionResponse complete(String apiKey, OpenCodeChatCompletionRequest request) {
            this.apiKey = apiKey;
            this.request = request;
            this.completeCalled = true;
            return new OpenCodeCompletionResponse(content, "stop", 10, 5, 15);
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
        assertThat(transport.request.temperature()).isEqualTo(0.0);
        assertThat(transport.request.maxTokens()).isGreaterThan(0);
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
    void gatewayUsesBoundedTaskSpecificTokenBudgets() {
        Map<AgentTaskType, Integer> expectedBudgets = Map.of(
                AgentTaskType.DRAFT_NODE, 1024,
                AgentTaskType.INTERPRET_ANSWER, 768,
                AgentTaskType.DRAFT_ANSWER_PATCH, 1024,
                AgentTaskType.DRAFT_SPEC, 2048);

        expectedBudgets.forEach((taskType, expectedBudget) -> {
            RecordingTransport transport = new RecordingTransport();
            gateway(transport, SELECTED_MODEL).run(request(taskType));
            assertThat(transport.request.maxTokens()).isEqualTo(expectedBudget);
        });
    }

    @Test
    void gatewayTracesPromptVersionAndHashes() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"action\":\"ask_next_question\",\"output\":{\"question\":\"q\"}}";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);
        ModelRequest request = request();

        ModelResponse response = gateway.run(request);

        assertThat(response.trace()).containsEntry("promptVersion", "draft-node.v1");
        assertThat(response.trace()).containsKey("promptHash");
        assertThat(response.trace()).containsKey("modelOutputHash");
        assertThat(response.trace().get("promptHash")).matches("[0-9a-f]{64}");
        assertThat(response.trace().get("modelOutputHash")).matches("[0-9a-f]{64}");
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
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE));
    }

    @Test
    void gatewayRejectsMissingAction() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"output\":{}}";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE));
    }

    @Test
    void gatewayRejectsUnknownAction() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"action\":\"fly\",\"output\":{}}";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE))
                .hasMessageContaining("fly");
    }

    @Test
    void gatewayRejectsMissingOutputObject() {
        RecordingTransport transport = new RecordingTransport();
        transport.content = "{\"action\":\"stop\"}";
        OpenCodeZenModelGateway gateway = gateway(transport, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE));
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
