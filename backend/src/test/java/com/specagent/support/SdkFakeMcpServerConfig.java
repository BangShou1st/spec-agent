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
 * 文件名:SdkFakeMcpServerConfig.java
 *
 * 测试目标:基于官方 SDK 的假 MCP 服务器,供集成测试使用。采用 SDK 的
 * 无状态 {@code McpServer} + {@code HttpServletStatelessServerTransport},
 * 使测试服务器与 {@code com.specagent.mcp} 客户端共用同一套协议实现——
 * 不手写消息帧;无状态设计让每个测试会话都面对同一个 servlet。
 *
 * 脚本化行为:一个带调用计数的工具({@link #TOOL_NAME})和一个文本资源。
 * 故障模式由各测试通过 {@link Fakes#setFailToolCall} 开关控制。
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
        // SDK 传输层只处理 URI 以其 message endpoint 结尾(默认 "/mcp")的请求。
        // 因此 servlet 映射在 /fake-mcp/*,endpoint 固定为 "/fake-mcp/mcp";
        // 不带该后缀的普通 /fake-mcp/* 请求会由传输层自身返回 404——
        // 该 404 不能与 Tomcat 路由失败混淆。
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

    /** 与服务器共享的、每个测试可变的脚本化状态。 */
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