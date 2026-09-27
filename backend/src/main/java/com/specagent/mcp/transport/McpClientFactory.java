package com.specagent.mcp.transport;

import com.specagent.common.network.OutboundNetworkPolicy;
import com.specagent.common.network.OutboundPolicyViolationException;
import com.specagent.mcp.McpProperties;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpPrompt;
import com.specagent.mcp.domain.McpResource;
import com.specagent.mcp.domain.McpTool;
import com.specagent.mcp.domain.McpToolResult;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文件名:McpClientFactory.java
 *
 * 用途:构造 MCP SDK 客户端的唯一位置。本类之上的代码只接触
 * {@code McpDiscovery}/{@code McpTool} 等领域类型;SDK 类绝不泄漏到
 * agent/brain/graph/policy/skill 等代码中。
 *
 * 传输方式:远程 Streamable HTTP(当前官方远程传输)。本地 stdio 被刻意延后。
 * 出站网络策略在任何连接建立之前强制执行;重定向/大小/超时限制也在这里生效。
 */
@Component
public class McpClientFactory {

    private final McpProperties properties;
    private final OutboundNetworkPolicy networkPolicy;

    public McpClientFactory(McpProperties properties, OutboundNetworkPolicy networkPolicy) {
        this.properties = properties;
        // 显式允许 localhost(测试/本地工具)时,仅为 MCP 边界放宽共享策略;
        // 其余 SSRF 防御保持不变。
        this.networkPolicy = properties.isAllowLocalhostHttp()
                ? new OutboundNetworkPolicy(true) : networkPolicy;
    }

    /**
     * 为一个连接打开远程 Streamable HTTP MCP 会话。
     *
     * @return 已完成初始化的会话;调用方负责 {@link Session#close()}
     * @throws McpTransportException 策略违规、握手或发现失败时抛出——
     *                               绝不抛出原始 SDK 错误
     */
    public Session open(String serverUrl, Map<String, Object> headers, String authHeader) {
        URI uri = validateUrl(serverUrl);
        // SDK 把最终 POST 目标解析为 baseUri + endpoint,而它的默认 endpoint
        // ("/mcp") 是绝对路径,会替换掉任何自定义基础路径。为了让配置的
        // Server URL 保持权威,这里做拆分:scheme+authority 作为 base,
        // URL 路径(如果存在)作为 endpoint。纯主机名则沿用 SDK 默认的
        // "/mcp" 约定。
        HttpClientStreamableHttpTransport.Builder builder =
                HttpClientStreamableHttpTransport
                        .builder(uri.getScheme() + "://" + uri.getAuthority())
                        .connectTimeout(Duration.ofMillis(properties.getDiscoveryTimeoutMs()));
        String path = uri.getRawPath();
        if (path != null && !path.isBlank() && !path.equals("/")) {
            builder.endpoint(path);
        }
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null) {
                    builder.customizeRequest(
                            request -> request.header(name, String.valueOf(value)));
                }
            });
        }
        if (authHeader != null && !authHeader.isBlank()) {
            // 由凭据派生的授权头只经传输层出去;绝不进入描述符、结果或模型上下文。
            builder.customizeRequest(request ->
                    request.header("Authorization", authHeader));
        }
        HttpClientStreamableHttpTransport transport = builder.build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(properties.getCallTimeoutMs()))
                .initializationTimeout(Duration.ofMillis(properties.getDiscoveryTimeoutMs()))
                .clientInfo(new McpSchema.Implementation("spec-agent", "0.1.0"))
                .build();
        try {
            client.initialize();
        } catch (RuntimeException ex) {
            try { client.close(); } catch (RuntimeException ignored) { }
            throw new McpTransportException("MCP handshake failed: "
                    + rootCauseMessage(ex));
        }
        return new SdkSession(client);
    }

    private URI validateUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new McpTransportException("MCP server URL is empty");
        }
        try {
            // 必须使用 HTTPS;策略同时拦截私网/链路本地/元数据主机。
            // 自定义 MCP Server 必须通过与 git 导入相同的出站门禁——
            // 一套策略,而不是两套临时检查。
            return networkPolicy.validateOutboundUrl(serverUrl.trim(),
                    properties.getConnectionMaxRedirects());
        } catch (OutboundPolicyViolationException ex) {
            throw new McpTransportException("MCP server rejected by outbound policy: "
                    + ex.getMessage());
        }
    }

    /** 规范化的、与具体传输无关的 MCP 会话。 */
    public interface Session extends AutoCloseable {
        McpDiscovery discover();
        McpToolResult callTool(String toolName, Map<String, Object> arguments);
        com.specagent.mcp.domain.McpResourceContent readResource(String uri);
        @Override
        void close();
    }

    private final class SdkSession implements Session {
        private final McpSyncClient client;

        SdkSession(McpSyncClient client) {
            this.client = client;
        }

        @Override
        public McpDiscovery discover() {
            try {
                McpSchema.ListToolsResult toolsResult = client.listTools();
                List<McpTool> tools = new ArrayList<>();
                for (McpSchema.Tool tool : toolsResult.tools()) {
                    tools.add(new McpTool(
                            bound(tool.name(), 256),
                            bound(tool.description(), properties.getMaxDescriptionChars()),
                            schemaToMap(tool.inputSchema()),
                            annotationsToMap(tool.annotations())));
                }

                List<McpResource> resources = new ArrayList<>();
                try {
                    McpSchema.ListResourcesResult resourcesResult = client.listResources();
                    for (McpSchema.Resource resource : resourcesResult.resources()) {
                        resources.add(new McpResource(
                                resource.uri() == null ? "" : resource.uri().toString(),
                                bound(resource.name(), 256),
                                bound(resource.description(), properties.getMaxDescriptionChars()),
                                bound(resource.mimeType(), 64)));
                    }
                } catch (RuntimeException ex) {
                    // Server 不支持 resources 是合法情况:该原始类型保持为空,
                    // 而不是让整个发现失败。
                }

                List<McpPrompt> prompts = new ArrayList<>();
                try {
                    McpSchema.ListPromptsResult promptsResult = client.listPrompts();
                    for (McpSchema.Prompt prompt : promptsResult.prompts()) {
                        prompts.add(new McpPrompt(
                                bound(prompt.name(), 256),
                                bound(prompt.description(), properties.getMaxDescriptionChars()),
                                prompt.arguments() == null ? 0 : prompt.arguments().size()));
                    }
                } catch (RuntimeException ex) {
                    // prompts 同理。
                }

                McpSchema.Implementation serverInfo = client.getServerInfo();
                return new McpDiscovery(
                        bound(serverInfo == null ? "" : serverInfo.name(), 128),
                        client.getCurrentInitializationResult() == null ? ""
                                : client.getCurrentInitializationResult().protocolVersion(),
                        tools, resources, prompts);
            } catch (RuntimeException ex) {
                throw new McpTransportException("MCP discovery failed: "
                        + rootCauseMessage(ex));
            }
        }

        @Override
        public McpToolResult callTool(String toolName, Map<String, Object> arguments) {
            try {
                McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                        toolName, arguments == null ? Map.of() : arguments);
                McpSchema.CallToolResult result = client.callTool(request);
                // MCP 把工具级失败建模为 isError=true 的结果,而不是传输异常
                // ——这里把它转成带类型的失败,让调用方能与成功的观测区分开。
                if (Boolean.TRUE.equals(result.isError())) {
                    Object content = extractContent(result);
                    String detail = "tool reported an error";
                    if (content instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if ("value".equals(String.valueOf(entry.getKey()))) {
                                detail = String.valueOf(entry.getValue());
                                break;
                            }
                        }
                    } else if (content != null) {
                        detail = String.valueOf(content);
                    }
                    return McpToolResult.failure("MCP tool reported an error: " + detail);
                }
                Object content = extractContent(result);
                return McpToolResult.ok(boundMap(content));
            } catch (RuntimeException ex) {
                return McpToolResult.failure("MCP tool call failed: "
                        + ex.getClass().getSimpleName());
            }
        }

        @Override
        public com.specagent.mcp.domain.McpResourceContent readResource(String uri) {
            try {
                McpSchema.ReadResourceResult result = client.readResource(
                        new McpSchema.ReadResourceRequest(uri));
                StringBuilder text = new StringBuilder();
                if (result.contents() != null) {
                    for (McpSchema.ResourceContents contents : result.contents()) {
                        if (contents instanceof McpSchema.TextResourceContents textContents) {
                            text.append(textContents.text());
                        }
                    }
                }
                return new com.specagent.mcp.domain.McpResourceContent(
                        uri, bound(text.toString(), properties.getResultMaxInlineBytes()),
                        "text/plain",
                        Map.of("kind", "MCP_RESOURCE", "uri", uri));
            } catch (RuntimeException ex) {
                throw new McpTransportException("MCP resource read failed: "
                        + ex.getClass().getSimpleName());
            }
        }

        @Override
        public void close() {
            try {
                client.closeGracefully();
            } catch (RuntimeException ignored) {
                try { client.close(); } catch (RuntimeException ignored2) { }
            }
        }
    }

    private String bound(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> boundMap(Object content) {
        if (!(content instanceof Map<?, ?> map)) {
            return Map.of("value", bound(String.valueOf(content),
                    properties.getResultMaxInlineBytes()));
        }
        // 按真实字节预算截断:过大的工具载荷只会保留有界前缀,巨型结果
        // 绝不可能污染模型上下文。为便于计量把值转成字符串;超出预算后的
        // 结构保真度被有意牺牲,以换取上下文安全。
        Map<String, Object> bounded = new java.util.LinkedHashMap<>();
        int budget = properties.getResultMaxInlineBytes();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (budget <= 0) {
                bounded.put("_truncated", true);
                break;
            }
            String text = String.valueOf(entry.getValue());
            int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > budget) {
                bounded.put(String.valueOf(entry.getKey()),
                        bound(text, budget) + "…[truncated]");
                budget = 0;
            } else {
                bounded.put(String.valueOf(entry.getKey()), entry.getValue());
                budget -= bytes;
            }
        }
        return bounded;
    }

    private Map<String, Object> annotationsToMap(McpSchema.ToolAnnotations annotations) {
        if (annotations == null) {
            return Map.of();
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        if (annotations.readOnlyHint() != null) {
            map.put("readOnlyHint", annotations.readOnlyHint());
        }
        if (annotations.destructiveHint() != null) {
            map.put("destructiveHint", annotations.destructiveHint());
        }
        if (annotations.idempotentHint() != null) {
            map.put("idempotentHint", annotations.idempotentHint());
        }
        return map;
    }

    /** 对 SDK 错误做尽力而为的根因摘要(绝不输出原始堆栈)。 */
    private String rootCauseMessage(RuntimeException ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message.substring(0, Math.min(message.length(), 200));
    }

    @SuppressWarnings("unchecked")
    private Object extractContent(McpSchema.CallToolResult result) {
        var contents = result.content();
        if (contents != null && !contents.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (var item : contents) {
                if (item instanceof McpSchema.TextContent textContent) {
                    text.append(textContent.text());
                }
            }
            if (text.length() > 0) {
                return Map.of("value", text.toString());
            }
        }
        if (Boolean.TRUE.equals(result.isError())) {
            return Map.of();
        }
        return Map.of();
    }

    /** 把 JsonSchema record 转换成有界、对模型安全的 Map。 */
    private Map<String, Object> schemaToMap(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return Map.of();
        }
        McpSchema.JsonSchema bounded = schema;
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        if (bounded.type() != null) {
            map.put("type", bounded.type());
        }
        if (bounded.properties() != null && !bounded.properties().isEmpty()) {
            Map<String, Object> props = new java.util.LinkedHashMap<>();
            for (var entry : bounded.properties().entrySet()) {
                props.put(entry.getKey(), boundMapValue(entry.getValue()));
            }
            map.put("properties", props);
        }
        if (bounded.required() != null && !bounded.required().isEmpty()) {
            map.put("required", bounded.required());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Object boundMapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> bounded = new java.util.LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (bounded.size() >= 20) {
                    break;
                }
                bounded.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return bounded;
        }
        return value;
    }
}