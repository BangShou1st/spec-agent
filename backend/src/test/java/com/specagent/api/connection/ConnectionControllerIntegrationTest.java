package com.specagent.api.connection;

import com.specagent.support.SdkFakeMcpServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connection management API closure: every lifecycle step is reachable
 * through product-level connectionId alone. No test reads the internal
 * row UUID as an API prerequisite. Secrets never appear in responses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SdkFakeMcpServerConfig.class)
@ActiveProfiles("test")
class ConnectionControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String fakeServerUrl;

    @BeforeEach
    void setUp() {
        fakeServerUrl = "http://127.0.0.1:" + port + "/fake-mcp/mcp";
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        clearTables();
    }

    @AfterEach
    void cleanup() {
        clearTables();
    }

    private void clearTables() {
        jdbcTemplate.update("DELETE FROM mcp_discovery_cache");
        jdbcTemplate.update("DELETE FROM connection_credentials");
        jdbcTemplate.update("DELETE FROM connections");
    }

    @SuppressWarnings("unchecked")
    private String createConnection(String name, String serverUrl, String secret) {
        Map<String, Object> body = secret == null
                ? Map.of("kind", "CUSTOM_MCP", "name", name, "config", Map.of("serverUrl", serverUrl))
                : Map.of("kind", "CUSTOM_MCP", "name", name, "config", Map.of("serverUrl", serverUrl), "secret", secret);
        ResponseEntity<Map> created = rest.postForEntity("/api/v1/connections", body, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String connectionId = (String) created.getBody().get("connectionId");
        assertThat(connectionId).startsWith("conn_");
        return connectionId;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> patchConnection(String connectionId, Map<String, Object> patch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(patch, headers);
        return rest.exchange("/api/v1/connections/" + connectionId, HttpMethod.PATCH, entity, Map.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void fullManagementLifecycleWithConnectionIdOnly() {
        String connectionId = createConnection("api-mcp", fakeServerUrl, "api-secret-token-xyz");

        ResponseEntity<Map> detail = rest.getForEntity("/api/v1/connections/" + connectionId, Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(detail.getBody())).doesNotContain("api-secret-token-xyz");
        assertThat((String) detail.getBody().get("maskedSuffix")).isEqualTo("****-xyz");
        assertThat(detail.getBody()).doesNotContainKey("credentialRef");
        Map<String, Object> config = (Map<String, Object>) detail.getBody().get("config");
        assertThat(config).containsEntry("serverUrl", fakeServerUrl);

        ResponseEntity<List> list = rest.getForEntity("/api/v1/connections", List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(list.getBody())).doesNotContain("api-secret-token-xyz");
        assertThat(String.valueOf(list.getBody())).contains(connectionId);

        ResponseEntity<Map> test = rest.postForEntity("/api/v1/connections/" + connectionId + "/test", null, Map.class);
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) test.getBody().get("toolCount")).isEqualTo(1);

        ResponseEntity<Map> connect = rest.postForEntity("/api/v1/connections/" + connectionId + "/connect", null, Map.class);
        assertThat(connect.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/enable", null, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> tools = rest.getForEntity("/api/v1/connections/" + connectionId + "/tools", List.class);
        assertThat(tools.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tools.getBody()).hasSize(1);
        Map<String, Object> tool = (Map<String, Object>) tools.getBody().get(0);
        assertThat(tool).containsKeys("name", "description", "inputSchema", "annotations");
        assertThat(String.valueOf(tools.getBody())).doesNotContain("api-secret-token-xyz");

        ResponseEntity<List> resources = rest.getForEntity("/api/v1/connections/" + connectionId + "/resources", List.class);
        assertThat(resources.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resources.getBody()).hasSize(1);

        ResponseEntity<List> prompts = rest.getForEntity("/api/v1/connections/" + connectionId + "/prompts", List.class);
        assertThat(prompts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prompts.getBody()).hasSize(1);

        ResponseEntity<Map> resource = rest.getForEntity(
                "/api/v1/connections/" + connectionId + "/resources/read?uri=" + SdkFakeMcpServerConfig.RESOURCE_URI,
                Map.class);
        assertThat(resource.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(resource.getBody().get("provenance"))).contains("MCP_RESOURCE");

        ResponseEntity<Map> refresh = rest.postForEntity("/api/v1/connections/" + connectionId + "/refresh", null, Map.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/disable", null, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        rest.delete("/api/v1/connections/" + connectionId);
        ResponseEntity<Map> gone = rest.getForEntity("/api/v1/connections/" + connectionId, Map.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(gone.getBody())).contains("CONNECTION_NOT_FOUND");
    }
    @SuppressWarnings("unchecked")
    @Test
    void badServerUrlIsRejectedWithTypedError() {
        String connectionId = createConnection("bad-mcp", "http://127.0.0.1:" + port + "/not-a-mcp-endpoint", null);
        ResponseEntity<Map> test = rest.postForEntity("/api/v1/connections/" + connectionId + "/test", null, Map.class);
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(test.getBody())).contains("CONNECTION_COMMAND_REJECTED");
    }

    @SuppressWarnings("unchecked")
    @Test
    void unknownConnectionIdReturnsTypedNotFound() {
        ResponseEntity<Map> detail = rest.getForEntity("/api/v1/connections/conn_doesnotexist1", Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(detail.getBody())).contains("CONNECTION_NOT_FOUND");

        ResponseEntity<Map> test = rest.postForEntity("/api/v1/connections/conn_doesnotexist1/test", null, Map.class);
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    @Test
    void secretLikeConfigIsRejected() {
        Map<String, Object> body = Map.of("kind", "CUSTOM_MCP", "name", "leaky",
                "config", Map.of("serverUrl", fakeServerUrl, "apiToken", "should-stay-in-secret-field"));
        ResponseEntity<Map> created = rest.postForEntity("/api/v1/connections", body, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(created.getBody())).contains("VALIDATION_ERROR");
    }

    @SuppressWarnings("unchecked")
    @Test
    void credentialUpdateInvalidatesDiscoveryUntilRevalidated() {
        String connectionId = createConnection("rotating", fakeServerUrl, "first-secret-aaaa");
        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/test", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/connect", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/enable", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<List> before = rest.getForEntity("/api/v1/connections/" + connectionId + "/tools", List.class);
        assertThat(before.getBody()).hasSize(1);

        ResponseEntity<Map> patched = patchConnection(connectionId, Map.of("secret", "second-secret-bbbb"));
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(patched.getBody())).doesNotContain("second-secret-bbbb");
        assertThat((Boolean) patched.getBody().get("enabled")).isFalse();
        assertThat((String) patched.getBody().get("status")).isEqualTo("CREATED");

        ResponseEntity<List> after = rest.getForEntity("/api/v1/connections/" + connectionId + "/tools", List.class);
        assertThat(after.getBody()).isEmpty();

        ResponseEntity<Void> enableAgain = rest.postForEntity("/api/v1/connections/" + connectionId + "/enable", null, Void.class);
        assertThat(enableAgain.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/test", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/connect", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/enable", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<List> revived = rest.getForEntity("/api/v1/connections/" + connectionId + "/tools", List.class);
        assertThat(revived.getBody()).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void renameOnlyPreservesLifecycleButEnforcesUniqueness() {
        String first = createConnection("alpha-name", fakeServerUrl, null);
        String second = createConnection("beta-name", fakeServerUrl, null);
        assertThat(rest.postForEntity("/api/v1/connections/" + first + "/test", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/v1/connections/" + first + "/connect", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/v1/connections/" + first + "/enable", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.postForEntity("/api/v1/connections/" + second + "/test", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/v1/connections/" + second + "/connect", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/v1/connections/" + second + "/enable", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> clash = patchConnection(second, Map.of("name", "alpha-name"));
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(clash.getBody())).contains("CONNECTION_COMMAND_REJECTED");

        ResponseEntity<Map> renamed = patchConnection(second, Map.of("name", "beta-renamed"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) renamed.getBody().get("name")).isEqualTo("beta-renamed");
        assertThat((String) renamed.getBody().get("status")).isEqualTo("CONNECTED");
        assertThat((Boolean) renamed.getBody().get("enabled")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void configUpdateResetsToPreTestState() {
        String connectionId = createConnection("cfg-change", fakeServerUrl, null);
        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/test", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/connect", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String badUrl = "http://127.0.0.1:" + port + "/not-a-mcp-endpoint";
        ResponseEntity<Map> patched = patchConnection(connectionId, Map.of("config", Map.of("serverUrl", badUrl)));
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) patched.getBody().get("status")).isEqualTo("CREATED");
        assertThat((Boolean) patched.getBody().get("enabled")).isFalse();

        ResponseEntity<List> tools = rest.getForEntity("/api/v1/connections/" + connectionId + "/tools", List.class);
        assertThat(tools.getBody()).isEmpty();

        ResponseEntity<Map> retest = rest.postForEntity("/api/v1/connections/" + connectionId + "/test", null, Map.class);
        assertThat(retest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @SuppressWarnings("unchecked")
    @Test
    void toolsEndpointKeepsBoundedShapeWithoutLeakage() {
        String connectionId = createConnection("shape-check", fakeServerUrl, "shape-secret-zzzz");
        assertThat(rest.postForEntity("/api/v1/connections/" + connectionId + "/connect", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<List> tools = rest.getForEntity("/api/v1/connections/" + connectionId + "/tools", List.class);
        assertThat(tools.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tools.getBody()).hasSize(1);
        Map<String, Object> tool = (Map<String, Object>) tools.getBody().get(0);
        assertThat(tool.keySet()).containsExactlyInAnyOrder("name", "description", "inputSchema", "annotations");
        String body = String.valueOf(tools.getBody());
        assertThat(body).doesNotContain("shape-secret-zzzz");
        assertThat(body).doesNotContain(fakeServerUrl);
        assertThat(body).doesNotContain("credentialRef");
    }

    @Test
    void emptyToolsBeforeDiscovery() {
        String connectionId = createConnection("fresh-no-cache", fakeServerUrl, null);
        ResponseEntity<List> tools = rest.getForEntity("/api/v1/connections/" + connectionId + "/tools", List.class);
        assertThat(tools.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tools.getBody()).isEmpty();
    }
}
