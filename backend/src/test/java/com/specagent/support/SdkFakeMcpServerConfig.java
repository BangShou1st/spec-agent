package com.specagent.support;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SDK-backed fake MCP server for integration tests. Uses the official SDK's
 * stateless {@code McpServer} + {@code HttpServletStatelessServerTransport}
 * so the test server and the {@code com.specagent.mcp} client share the same
 * protocol implementation — no hand-rolled framing, and stateless so every
 * test session works against the same servlet.
 *
 * <p>Scripted behavior: one call-counting tool ({@link #TOOL_NAME}) and one
 * text resource. Failure modes toggled per test via {@link #state()}.
 */
@Configuration
public class SdkFakeMcpServerConfig {

    public static final String TOOL_NAME = "read_docs";
    public static final String RESOURCE_URI = "docs://guide";

    @Bean
    public Fakes sdkFakeMcpState() {
        return new Fakes();
    }

    @Bean
    public HttpServletStatelessServerTransport fakeMcpTransport() {
        // The SDK transport only handles requests whose URI ends with its
        // message endpoint (default "/mcp"). The servlet is therefore mapped
        // at /fake-mcp/* and the endpoint stays "/fake-mcp/mcp" so plain
        // /fake-mcp/* requests without that suffix keep returning 404 from
        // the transport itself — that 404 must not be confused with a Tomcat
        // routing failure.
        return HttpServletStatelessServerTransport.builder()
                .messageEndpoint("/fake-mcp/mcp")
                .build();
    }

    @Bean
    public io.modelcontextprotocol.server.McpStatelessSyncServer fakeMcpServer(
            Fakes fakes, HttpServletStatelessServerTransport transport) {
        McpSchema.JsonSchema jsonSchema = new McpSchema.JsonSchema("object",
                Map.of("query", Map.of("type", "string")),
                List.of("query"), null, null, Map.of());
        McpSchema.Tool tool = new McpSchema.Tool(TOOL_NAME, null,
                "Fake MCP tool for tests", jsonSchema, null, null, Map.of());

        McpStatelessServerFeatures.SyncToolSpecification toolSpec =
                new McpStatelessServerFeatures.SyncToolSpecification(tool, (ctx, request) -> {
                    fakes.calls().incrementAndGet();
                    if (fakes.failToolCall()) {
                        return McpSchema.CallToolResult.builder()
                                .isError(true)
                                .addTextContent("tool call failed")
                                .build();
                    }
                    Object query = request.arguments() == null ? ""
                            : String.valueOf(request.arguments().get("query"));
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("{\"summary\": \"faked summary\", \"query\": \""
                                    + query + "\"}")
                            .build();
                });

        McpSchema.Resource resource = new McpSchema.Resource(RESOURCE_URI, "fake-resource",
                "fake-resource", "text/plain", null, null, null, Map.of());
        McpStatelessServerFeatures.SyncResourceSpecification resourceSpec =
                new McpStatelessServerFeatures.SyncResourceSpecification(resource,
                        (ctx, request) -> new McpSchema.ReadResourceResult(List.of(
                                new McpSchema.TextResourceContents(
                                        RESOURCE_URI, "text/plain",
                                        "fake resource body: 迁移检查清单\n1. 备份\n2. 验证"))));

        McpSchema.Prompt prompt = new McpSchema.Prompt("fake-prompt", "Fake prompt asset",
                List.of(new McpSchema.PromptArgument("topic", "topic to cover", null)));
        McpStatelessServerFeatures.SyncPromptSpecification promptSpec =
                new McpStatelessServerFeatures.SyncPromptSpecification(prompt,
                        (ctx, request) -> new McpSchema.GetPromptResult(
                                "fake prompt result", List.of(
                                        new McpSchema.PromptMessage(McpSchema.Role.USER,
                                                new McpSchema.TextContent(
                                                        "You are a helpful assistant about the topic.")))));

        return McpServer.sync(transport)
                .serverInfo("sdk-fake-mcp", "1.0.0")
                .tools(List.of(toolSpec))
                .resources(List.of(resourceSpec))
                .prompts(List.of(promptSpec))
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport>
            fakeMcpServletRegistration(HttpServletStatelessServerTransport transport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(transport);
        registration.addUrlMappings("/fake-mcp/*");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /** Mutable per-test scripted state shared with the server. */
    public static final class Fakes {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean failToolCall;

        public AtomicInteger calls() {
            return calls;
        }

        public boolean failToolCall() {
            return failToolCall;
        }

        public void setFailToolCall(boolean flag) {
            this.failToolCall = flag;
        }
    }
}