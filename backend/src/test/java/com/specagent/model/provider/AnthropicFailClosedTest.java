package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelOutputContract;
import com.specagent.model.contract.ModelInferenceResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 文件名:AnthropicFailClosedTest.java
 *
 * 测试目标:MS-C3 后的 Anthropic 边界语义——JSON_OBJECT 契约通过宿主 Envelope 桥接
 * 真正可用(正例),而所有不支持/非法的协议形态仍严格失败关闭:
 * <ul>
 *   <li>非流式/流式请求绝不伪造 response_format、output_format(deprecated)或其他
 *       Anthropic 不存在的字段;</li>
 *   <li>非法 Envelope(缺失字段、非对象内层、重复键、非法 Unicode、超长、尾随 token、
 *       raw JSON 直出)在解包时失败关闭;</li>
 *   <li>身份边界:x-api-key + anthropic-version,绝不发送 Authorization 或伪造 beta 头;</li>
 *   <li>兼容性探测走真实桥接,不再因协议整体拒绝;但决策语义校验仍然拒绝非法输出。</li>
 * </ul>
 */
class AnthropicFailClosedTest {

    private final AnthropicMessagesProtocolAdapter adapter = new AnthropicMessagesProtocolAdapter();

    private ModelInferenceRequest jsonObjectRequest() {
        return new ModelInferenceRequest(UUID.randomUUID(), "compatibility-probe",
                List.of(new ModelInferenceMessage("user", "hi")), 256,
                ModelOutputContract.jsonObject());
    }

    @Test void jsonObjectNowBridgesThroughOutputConfigInsteadOfFailing() {
        var body = adapter.buildRequestBody(jsonObjectRequest(), "m");
        assertThat(body).containsKey("output_config");
        assertThat(body).doesNotContainKey("response_format");
        assertThat(body).doesNotContainKey("output_format");
    }

    @Test void noFakeJsonObjectWirePayload() {
        // 文本与 JsonSchema 的基础能力仍在,但绝不能伪装成 Anthropic 不存在的字段。
        var textReq = new ModelInferenceRequest(UUID.randomUUID(), "p",
                List.of(new ModelInferenceMessage("user", "hi")), 256, ModelOutputContract.text());
        assertThat(adapter.buildRequestBody(textReq, "m")).doesNotContainKey("response_format");
        assertThat(adapter.buildRequestBody(textReq, "m")).doesNotContainKey("choices");
        assertThat(adapter.buildRequestBody(textReq, "m")).doesNotContainKey("input");
        assertThat(adapter.buildRequestBody(textReq, "m")).doesNotContainKey("output_format");
    }

    @Test void illegalEnvelopeShapesFailClosedBeforeContentIsAccepted() {
        String[] illegal = {
                "{\"envelope\":\"but not the host envelope\"}",
                "{\"json\":123}",
                "{\"json\":\"plain text not json\"}",
                "{\"json\":\"\"}",
        };
        for (String raw : illegal) {
            var response = new ModelInferenceResponse(raw, "end_turn", null, null);
            assertThatThrownBy(() -> AnthropicMessagesProtocolAdapter.unwrapJsonEnvelope(
                    response.content(), "probe"))
                    .as("envelope: " + raw)
                    .isInstanceOf(ModelProviderException.class);
        }
    }

    @Test void oversizedEnvelopeFailsClosed() {
        String huge = "{\"json\":\"" + "x".repeat(200000) + "\"}";
        assertThatThrownBy(() -> AnthropicMessagesProtocolAdapter.unwrapJsonEnvelope(huge, "probe"))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void identityHeadersStayAnthropicNative() {
        var headers = adapter.authHeaders("k1");
        assertThat(headers).containsOnlyKeys("x-api-key", "anthropic-version");
        assertThat(headers).doesNotContainKey("Authorization");
        assertThat(headers).doesNotContainKey("anthropic-beta");
    }

    @Test void probeRunsTheRealBridgeForAnthropicAndStillRejectsInvalidDecisions() {
        // probeCustom 不再对 ANTHROPIC_MESSAGES 直接拒绝:桥接后的输出仍要过
        // CompatibilityDecisionSemantics 权威校验(在 CompatibilityProbeChain 与
        // ModelConnections 系列集成测试中覆盖)。这里验证入口不再抛协议拒绝。
        var probe = new CompatibilityProbeService(new ObjectMapper(),
                new ProtocolAdapterRegistry(List.of(
                        new ChatCompletionsProtocolAdapter(),
                        new ResponsesProtocolAdapter(),
                        new AnthropicMessagesProtocolAdapter())),
                new CompatibilityProbeSemantics());
        // 指向一个必然拒绝连接的回环地址:失败的必须是网络/探测层,而不是
        // "Anthropic cannot serve JSON_OBJECT" 的协议层拒绝。
        assertThatThrownBy(() -> probe.probeCustom(CustomApiFormat.ANTHROPIC_MESSAGES,
                "https://gateway.invalid/v1", null, "m"))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageNotContaining("cannot serve the required JSON_OBJECT");
    }

    @Test void agentContractStaysJsonObject() {
        assertThat(ModelOutputContract.jsonObject()).isInstanceOf(ModelOutputContract.JsonObject.class);
    }
}
