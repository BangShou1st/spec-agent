package com.specagent.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件名:OpenCodeVerifiedChatMetadataStub.java
 *
 * 测试辅助类:本机 OpenCode 模型目录元数据 stub,供需要"协议经元数据确认为
 * Chat Completions"的运行期门禁测试使用。
 *
 * <p>只把通过 {@link #registerChatModel(String)} 显式登记的模型 id 放进目录
 * (provider.npm 缺省即提供商级 {@code @ai-sdk/openai-compatible} → CHAT,
 * cost 缺省 → 非免费,无下线标记);未登记的模型 id 不出现在目录里,严格复刻
 * "未登记即未验证"的真实语义。只监听 {@code 127.0.0.1},绝不访问公网。
 */
public final class OpenCodeVerifiedChatMetadataStub {

    private static final Set<String> CHAT_MODELS = ConcurrentHashMap.newKeySet();
    private static final HttpServer SERVER = start();

    private OpenCodeVerifiedChatMetadataStub() {
    }

    /** 供 {@code @DynamicPropertySource} 使用的本机元数据 URL。 */
    public static String url() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/api.json";
    }

    /** 登记一个"协议经官方目录元数据确认为 Chat Completions"的模型 id。 */
    public static void registerChatModel(String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            CHAT_MODELS.add(modelId.trim());
        }
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api.json", OpenCodeVerifiedChatMetadataStub::respond);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start OpenCode metadata stub", ex);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        StringBuilder models = new StringBuilder();
        for (String id : List.copyOf(CHAT_MODELS)) {
            if (models.length() > 0) {
                models.append(',');
            }
            models.append('"').append(id).append("\":{}");
        }
        String payload = "{\"opencode\":{\"id\":\"opencode\",\"npm\":\"@ai-sdk/openai-compatible\","
                + "\"models\":{" + models + "}}}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
