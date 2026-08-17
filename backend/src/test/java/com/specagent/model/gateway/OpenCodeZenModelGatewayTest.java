package com.specagent.model.gateway;

import com.specagent.agent.AgentAction;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;
import com.specagent.credential.OpenCodeCredentialService;
import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeChatMessage;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeModelList;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenCodeZenModelGatewayTest {

    private static final String API_KEY = "sk-test-key";
    private static final String SELECTED_MODEL = "mimo-v2.5-free";

    private final OpenCodeCredentialService credentials = mock(OpenCodeCredentialService.class);

    private static final class RecordingTransport implements OpenCodeZenTransport {
        String apiKey;
        OpenCodeChatCompletionRequest request;
        OpenCodeCompletionResponse response =
                new OpenCodeCompletionResponse("{\"question\":\"what?\"}", "stop", 10, 5, 15);

        @Override
        public OpenCodeCompletionResponse complete(String apiKey, OpenCodeChatCompletionRequest request) {
            this.apiKey = apiKey;
            this.request = request;
            return response;
        }

        @Override
        public OpenCodeModelList listModels(String apiKey) {
            throw new UnsupportedOperationException("gateway test does not discover models");
        }

        @Override
        public void validateCredential(String apiKey) {
            throw new UnsupportedOperationException("gateway test does not probe");
        }
    }

    private ModelRequest request(Map<String, String> metadata) {
        return new ModelRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AgentTaskType.DRAFT_NODE, "{\"input\":true}", metadata);
    }

    @Test
    void gatewayResolvesCredentialAndMapsCompletionToModelResponse() {
        when(credentials.resolveOpenCode()).thenReturn(Optional.of(API_KEY));
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway =
                new OpenCodeZenModelGateway(transport, credentials, SELECTED_MODEL);

        ModelRequest request = request(Map.of(ModelRequest.METADATA_EXPECTED_ACTION, "ask_next_question"));
        ModelResponse response = gateway.run(request);

        // Transport received the resolved credential and the minimal payload.
        assertThat(transport.apiKey).isEqualTo(API_KEY);
        assertThat(transport.request.model()).isEqualTo(SELECTED_MODEL);
        assertThat(transport.request.temperature()).isEqualTo(0.0);
        assertThat(transport.request.maxTokens()).isGreaterThan(0);
        assertThat(transport.request.messages()).hasSize(2);
        assertThat(transport.request.messages().get(0).role()).isEqualTo("system");
        assertThat(transport.request.messages().get(1).role()).isEqualTo("user");
        assertThat(transport.request.messages().get(1).content())
                .contains(AgentTaskType.DRAFT_NODE.code())
                .contains(request.inputJson());

        // Response echoes the exact request correlation and runtime action.
        assertThat(response.requestAgentRunId()).isEqualTo(request.agentRunId());
        assertThat(response.requestContextSnapshotId()).isEqualTo(request.contextSnapshotId());
        assertThat(response.taskType()).isEqualTo(AgentTaskType.DRAFT_NODE);
        assertThat(response.action()).isEqualTo(AgentAction.ASK_NEXT_QUESTION);
        assertThat(response.outputJson()).isEqualTo(transport.response.content());
        assertThat(response.trace()).containsEntry("adapter", "opencode-zen");
        assertThat(response.trace()).containsEntry("model", SELECTED_MODEL);
    }

    @Test
    void gatewayRejectsMissingCredentialBeforeCallingTransport() {
        when(credentials.resolveOpenCode()).thenReturn(Optional.empty());
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway =
                new OpenCodeZenModelGateway(transport, credentials, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request(Map.of(ModelRequest.METADATA_EXPECTED_ACTION, "ask_next_question"))))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.NOT_CONFIGURED));
        assertThat(transport.request).isNull();
    }

    @Test
    void gatewayRejectsBlankSelectedModel() {
        when(credentials.resolveOpenCode()).thenReturn(Optional.of(API_KEY));
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway = new OpenCodeZenModelGateway(transport, credentials, "  ");

        assertThatThrownBy(() -> gateway.run(request(Map.of(ModelRequest.METADATA_EXPECTED_ACTION, "ask_next_question"))))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.NOT_CONFIGURED));
        assertThat(transport.request).isNull();
    }

    @Test
    void gatewayRejectsRequestWithoutExpectedActionMetadata() {
        when(credentials.resolveOpenCode()).thenReturn(Optional.of(API_KEY));
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway =
                new OpenCodeZenModelGateway(transport, credentials, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request(Map.of())))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE));
        assertThat(transport.request).isNull();
    }

    @Test
    void gatewayPropagatesTransportFailures() {
        when(credentials.resolveOpenCode()).thenReturn(Optional.of(API_KEY));
        OpenCodeZenTransport failingTransport = new OpenCodeZenTransport() {
            @Override
            public OpenCodeCompletionResponse complete(String apiKey, OpenCodeChatCompletionRequest request) {
                throw new OpenCodeModelException(OpenCodeModelErrorCategory.EMPTY_CONTENT,
                        "OpenCode returned empty model content");
            }

            @Override
            public OpenCodeModelList listModels(String apiKey) {
                throw new UnsupportedOperationException("gateway test does not discover models");
            }

            @Override
            public void validateCredential(String apiKey) {
                throw new UnsupportedOperationException("gateway test does not probe");
            }
        };
        OpenCodeZenModelGateway gateway =
                new OpenCodeZenModelGateway(failingTransport, credentials, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request(Map.of(ModelRequest.METADATA_EXPECTED_ACTION, "ask_next_question"))))
                .isInstanceOf(OpenCodeModelException.class)
                .hasMessageContaining("empty model content");
    }

    @Test
    void gatewayMessageNeverContainsApiKey() {
        when(credentials.resolveOpenCode()).thenReturn(Optional.of(API_KEY));
        RecordingTransport transport = new RecordingTransport();
        OpenCodeZenModelGateway gateway =
                new OpenCodeZenModelGateway(transport, credentials, SELECTED_MODEL);

        assertThatThrownBy(() -> gateway.run(request(Map.of())))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(API_KEY));
    }
}