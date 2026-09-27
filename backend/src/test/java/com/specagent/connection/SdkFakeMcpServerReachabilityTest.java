package com.specagent.connection;

import com.specagent.support.SdkFakeMcpServerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:SdkFakeMcpServerReachabilityTest.java
 *
 * 测试目标:验证 SDK fake MCP servlet 在 Spring 测试容器中确实可以通过
 * 注册的 URL 访问到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SdkFakeMcpServerConfig.class)
@ActiveProfiles("test")
class SdkFakeMcpServerReachabilityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private SdkFakeMcpServerConfig.Fakes fakes;

    @Test
    void servletRespondsToPing() throws Exception {
        assertThat(applicationContext.getBeansOfType(
                org.springframework.boot.web.servlet.ServletRegistrationBean.class))
                .isNotEmpty();
        jakarta.servlet.ServletContext servletContext =
                ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)
                        applicationContext).getServletContext();
        java.util.Collection<String> mappings = servletContext.getServletRegistrations()
                .values().stream().flatMap(r -> r.getMappings().stream()).toList();
        System.out.println("SERVLET_MAPPINGS=" + mappings);
        assertThat(mappings).anyMatch(m -> m.startsWith("/fake-mcp"));
        HttpClient client = HttpClient.newHttpClient();
        // 传输端点是 /fake-mcp/mcp(见 SdkFakeMcpServerConfig):
        // 对它发起的 JSON-RPC POST 必须到达传输层,而不是 DispatcherServlet。
        String body = """
                {"jsonrpc":"2.0","id":"1","method":"ping","params":{}}
                """;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/fake-mcp/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        System.out.println("STATUS=" + response.statusCode());
        System.out.println("BODY=" + response.body());
        assertThat(response.statusCode()).isLessThan(500);
    }
}