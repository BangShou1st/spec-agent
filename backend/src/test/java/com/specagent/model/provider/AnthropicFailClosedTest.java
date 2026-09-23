package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.model.GlobalAssistantDecisionParser;
import com.specagent.assistant.model.GlobalAssistantDecisionSemanticsAdapter;
import com.specagent.assistant.model.GlobalAssistantDecisionValidator;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelOutputContract;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * V1 frozen decision: Anthropic Messages cannot serve the GA production
 * JSON_OBJECT contract. It must fail before any HTTP request, never with a
 * guessed wire payload, and never become validatable or activatable.
 */
class AnthropicFailClosedTest {

    private final AnthropicMessagesProtocolAdapter adapter = new AnthropicMessagesProtocolAdapter();

    private ModelInferenceRequest jsonObjectRequest() {
        return new ModelInferenceRequest(UUID.randomUUID(), "compatibility-probe",
                List.of(new ModelInferenceMessage("user", "hi")), 256,
                ModelOutputContract.jsonObject());
    }

    @Test void jsonObjectFailsBeforeHttp() {
        assertThatThrownBy(() -> adapter.buildRequestBody(jsonObjectRequest(), "m"))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void noFakeJsonObjectWirePayload() {
        // Text and JsonSchema groundwork remain but must never claim the
        // GA production contract shape.
        var textReq = new ModelInferenceRequest(UUID.randomUUID(), "p",
                List.of(new ModelInferenceMessage("user", "hi")), 256, ModelOutputContract.text());
        assertThat(adapter.buildRequestBody(textReq, "m")).doesNotContainKey("response_format");
        assertThat(adapter.buildRequestBody(textReq, "m")).doesNotContainKey("choices");
        assertThat(adapter.buildRequestBody(textReq, "m")).doesNotContainKey("input");
    }

    @Test void probeRefusesAnthropicWithoutHttp() {
        var probe = new CompatibilityProbeService(new ObjectMapper(),
                new ProtocolAdapterRegistry(List.of(
                        new ChatCompletionsProtocolAdapter(),
                        new ResponsesProtocolAdapter(),
                        new AnthropicMessagesProtocolAdapter())),
                new GlobalAssistantDecisionSemanticsAdapter(new GlobalAssistantDecisionParser(new ObjectMapper()),
                        new GlobalAssistantDecisionValidator()));
        assertThatThrownBy(() -> probe.probeCustom(
                CustomApiFormat.ANTHROPIC_MESSAGES, "https://gateway.example/v1", null, "m"))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void agentContractStaysJsonObject() {
        assertThat(ModelOutputContract.jsonObject()).isInstanceOf(ModelOutputContract.JsonObject.class);
    }
}
