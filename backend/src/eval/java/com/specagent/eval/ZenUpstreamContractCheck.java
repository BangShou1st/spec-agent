package com.specagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件名:ZenUpstreamContractCheck.java
 *
 * 用途:OpenCode Zen 上游**请求契约**的可执行门禁。
 *
 * 判据(与已验证可用的参考客户端一致):只要请求构造正确,上游只会返回
 * **200** 或 **429**(免费档配额耗尽)。**任何其他状态码**(尤其 403
 * {@code FreeTierError})都表示请求本身有问题——最常见的原因是 {@code ses_}/
 * {@code msg_} 标识符形态或计数器不符合冻结契约,或请求缺少 CLI 核心工具信封。
 *
 * 本检查用**共享库自己的构造路径**发请求({@link OpenCodeZenRequestFactory} 生成
 * 标识符 + {@link OpenCodeZenProtocolPolicy} 的 CLI 信封),因此它验证的正是
 * "任何只用共享库接口的项目能否稳定拿到 200/429"。它走直连(与产品一致的
 * {@code NO_PROXY}),绝不经过本地兼容代理。
 *
 * 显式运行:`backend> gradlew.bat zenUpstreamContract`
 * 可用 {@code SPEC_AGENT_ZEN_CONTRACT_MODEL} 覆盖模型(默认 {@code mimo-v2.6-flash-free})。
 */
public final class ZenUpstreamContractCheck {

    private ZenUpstreamContractCheck() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OpenCodeZenRequestFactory factory =
                new OpenCodeZenRequestFactory(OpenCodeZenProtocolPolicy.USER_AGENT);

        String model = System.getenv().getOrDefault(
                "SPEC_AGENT_ZEN_CONTRACT_MODEL", "mimo-v2.6-flash-free");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(Map.of("role", "user",
                "content", "reply with the single word: ok")));
        payload.put("max_tokens", 16);
        OpenCodeZenProtocolPolicy.applyRequiredChatEnvelope(payload, true);

        URI endpoint = URI.create(OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL
                + OpenCodeZenProtocolPolicy.CHAT_COMPLETIONS_PATH);
        // Free-tier public credential; the factory generates the shaped identifiers.
        HttpRequest request = factory.newEphemeralRequest(endpoint, true, null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)))
                .build();

        HttpClient client = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        System.out.println("[zen-contract] POST " + endpoint + " model=" + model);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        System.out.println("[zen-contract] HTTP " + status + " body=" + snippet(response.body()));

        if (status != 200 && status != 429) {
            System.err.println("[zen-contract] FAIL: expected 200 or 429, got " + status
                    + " — the request construction is not accepted by upstream.");
            System.exit(1);
        }
        System.out.println("[zen-contract] OK: upstream recognised the shared-library request"
                + " (200 or 429).");
    }

    private static String snippet(String body) {
        if (body == null) {
            return "<empty>";
        }
        String single = body.replaceAll("[\\r\\n\\t]", " ").trim();
        return single.length() <= 300 ? single : single.substring(0, 300) + "…";
    }
}
