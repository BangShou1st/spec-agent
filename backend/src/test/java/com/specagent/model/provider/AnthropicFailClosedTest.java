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
 * 文件名:AnthropicFailClosedTest.java
 *
 * 测试目标:验证 V1 冻结决策——Anthropic Messages 协议不能承载 GA 生产所需的
 * JSON_OBJECT 输出契约:必须在发起任何 HTTP 请求之前就失败(不发出猜测的报文),
 * 兼容性探测对 Anthropic 格式直接拒绝,且智能体决策契约始终保持 JSON_OBJECT。
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
        // 文本与 JsonSchema 的基础能力仍在,但绝不能伪装成 GA 生产的契约形态。
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
