package com.specagent.mcp.transport;

import com.specagent.common.network.OutboundNetworkPolicy;
import com.specagent.common.network.OutboundPolicyViolationException;
import com.specagent.mcp.config.McpProperties;
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
 * Sole place that constructs MCP SDK clients. Everything above this class
 * speaks only the {@code McpDiscovery}/{@code McpTool}... domain types; SDK
 * classes never leak into agent/brain/graph/policy/skill code.
 *
 * <p>Transport choice: Remote Streamable HTTP (the current official remote
 * transport). Local stdio is deliberately deferred. The outbound network
 * policy is enforced before any connection, and redirect/size/timeout limits
 * are applied here.
 */
@Component
public class McpClientFactory {

    private final McpProperties properties;
    private final OutboundNetworkPolicy networkPolicy;

    public McpClientFactory(McpProperties properties, OutboundNetworkPolicy networkPolicy) {
        this.properties = properties;
        // Explicit localhost opt-in (test/local tooling) relaxes the shared
        // policy for the MCP boundary only; SSRF defense stays otherwise.
        this.networkPolicy = properties.isAllowLocalhostHttp()
                ? new OutboundNetworkPolicy(true) : networkPolicy;
    }

    /**
     * Opens a remote Streamable HTTP MCP session for one connection.
     *
     * @return an initialized session; caller owns {@link Session#close()}
     * @throws McpTransportException on policy violation, handshake, or
     *                               discovery failure — never a raw SDK error
     */
    public Session open(String serverUrl, Map<String, Object> headers, String authHeader) {
        URI uri = validateUrl(serverUrl);
        // The SDK resolves the final POST target as baseUri + endpoint, and
        // its default endpoint ("/mcp") is an absolute path that would replace
        // any custom base path. To keep the configured server URL
        // authoritative, split it: scheme+authority stays the base and the URL
        // path (when present) becomes the endpoint. A bare host keeps the SDK
        // default "/mcp" convention.
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
            // Credential-derived authorization leaves through the transport
            // only; it never enters descriptors, results, or model context.
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
            // HTTPS required; the policy also blocks private/link-local/
            // metadata hosts. Custom MCP servers must pass the same outbound
            // gate as git imports — one policy, not two ad-hoc checks.
            return networkPolicy.validateOutboundUrl(serverUrl.trim(),
                    properties.getConnectionMaxRedirects());
        } catch (OutboundPolicyViolationException ex) {
            throw new McpTransportException("MCP server rejected by outbound policy: "
                    + ex.getMessage());
        }
    }

    /** Normalized, transport-agnostic MCP session. */
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
                    // A server without resources support is legitimate: the
                    // primitive just stays empty rather than failing discovery.
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
                    // Same for prompts.
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
                // MCP models tool-level failure as a result with isError=true,
                // not a transport exception — surface it as a typed failure so
                // callers can distinguish it from a successful observation.
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
        // True byte budget: large tool payloads are truncated to a bounded
        // prefix so a giant result can never pollute model context. Values
        // are stringified for measurement; structured fidelity beyond the
        // bound is intentionally sacrificed for context safety.
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

    /** Best-effort root-cause summary for SDK errors (never raw stack). */
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

    /** Converts a JsonSchema record into a bounded, model-safe map. */
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