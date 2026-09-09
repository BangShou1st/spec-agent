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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connection management API: create/test/connect/enable/disable/delete plus
 * resource/prompt inspection. Secrets never appear in responses — only the
 * masked suffix and the opaque credential ref do.
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
    @Test
    void fullManagementLifecycleHidesSecrets() {
        Map<String, Object> createBody = Map.of(
                "kind", "CUSTOM_MCP",
                "name", "api-mcp",
                "config", Map.of("serverUrl", fakeServerUrl),
                "secret", "api-secret-token-xyz");
        ResponseEntity<Map> created = rest.postForEntity(
                "/api/v1/connections", createBody, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String connectionId = (String) created.getBody().get("connectionId");
        assertThat(connectionId).startsWith("conn_");
        String rowId = jdbcTemplate.queryForObject(
                "SELECT id FROM connections WHERE connection_id = ?",
                String.class, connectionId);

        // Detail exposes the masked suffix, never the plaintext.
        ResponseEntity<Map> detail = rest.getForEntity(
                "/api/v1/connections/" + connectionId, Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(detail.getBody())).doesNotContain("api-secret-token-xyz");
        assertThat((String) detail.getBody().get("maskedSuffix")).isEqualTo("****-xyz");

        // List stays secret-free too.
        ResponseEntity<java.util.List> list = rest.getForEntity(
                "/api/v1/connections", java.util.List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(list.getBody())).doesNotContain("api-secret-token-xyz");

        // Test discovers the fake tool; connect then enables planner visibility.
        ResponseEntity<Map> test = rest.postForEntity(
                "/api/v1/connections/" + rowId + "/test", null, Map.class);
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) test.getBody().get("toolCount")).isEqualTo(1);

        ResponseEntity<Map> connect = rest.postForEntity(
                "/api/v1/connections/" + rowId + "/connect", null, Map.class);
        assertThat(connect.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rest.postForEntity(
                "/api/v1/connections/" + rowId + "/enable", null, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Resources/prompts keep their own semantics (provenance, no flattening).
        ResponseEntity<java.util.List> resources = rest.getForEntity(
                "/api/v1/connections/" + rowId + "/resources", java.util.List.class);
        assertThat(resources.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resources.getBody()).hasSize(1);

        ResponseEntity<java.util.List> prompts = rest.getForEntity(
                "/api/v1/connections/" + rowId + "/prompts", java.util.List.class);
        assertThat(prompts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prompts.getBody()).hasSize(1);

        ResponseEntity<Map> resource = rest.getForEntity(
                "/api/v1/connections/" + rowId + "/resources/read?uri="
                        + SdkFakeMcpServerConfig.RESOURCE_URI,
                Map.class);
        assertThat(resource.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(resource.getBody().get("provenance")))
                .contains("MCP_RESOURCE");

        assertThat(rest.postForEntity(
                "/api/v1/connections/" + rowId + "/disable", null, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        rest.delete("/api/v1/connections/" + rowId);
        ResponseEntity<Map> gone = rest.getForEntity(
                "/api/v1/connections/" + connectionId, Map.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void badServerUrlIsRejectedWithTypedError() {
        UUID rowId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO connections (id, connection_id, name, kind, status,"
                        + " enabled, config, credential_ref, last_error,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                rowId, "conn_bad_" + rowId.toString().substring(0, 8), "bad-mcp",
                "CUSTOM_MCP", "CREATED", false,
                "{\"serverUrl\": \"http://127.0.0.1:" + port + "/not-a-mcp-endpoint\"}",
                null, null);
        ResponseEntity<Map> test = rest.postForEntity(
                "/api/v1/connections/" + rowId + "/test", null, Map.class);
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(test.getBody()))
                .contains("CONNECTION_COMMAND_REJECTED");
    }
}
