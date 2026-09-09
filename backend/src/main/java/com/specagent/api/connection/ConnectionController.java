package com.specagent.api.connection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.connection.domain.Connection;
import com.specagent.connection.domain.ConnectionKind;
import com.specagent.connection.service.ConnectionLifecycleService;
import com.specagent.connection.service.ConnectionValidationException;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpPrompt;
import com.specagent.mcp.domain.McpResource;
import com.specagent.mcp.provider.McpPromptAssetProvider;
import com.specagent.mcp.provider.McpResourceProvider;
import com.specagent.mcp.runtime.McpDiscoveryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Connection management backend API. Connection (product concept) and MCP
 * (protocol) stay separate in the model; the API exposes both facets. Auth
 * callbacks stay in the auth layer; this surface is the management contract
 * the future Connections UI consumes.
 *
 * <p>Public identity is product-level connectionId on every endpoint. The
 * internal row UUID never becomes the public frontend contract. Secrets
 * never appear in responses: only the masked suffix and a has-credential
 * flag are exposed. Config is validated non-secret metadata.
 */
@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {

    private final ConnectionLifecycleService lifecycleService;
    private final McpResourceProvider resourceProvider;
    private final McpPromptAssetProvider promptAssetProvider;
    private final McpDiscoveryService discoveryService;
    private final com.specagent.connection.credentials.SecretStore secretStore;
    private final ObjectMapper objectMapper;

    public ConnectionController(ConnectionLifecycleService lifecycleService,
                                McpResourceProvider resourceProvider,
                                McpPromptAssetProvider promptAssetProvider,
                                McpDiscoveryService discoveryService,
                                com.specagent.connection.credentials.SecretStore secretStore,
                                ObjectMapper objectMapper) {
        this.lifecycleService = lifecycleService;
        this.resourceProvider = resourceProvider;
        this.promptAssetProvider = promptAssetProvider;
        this.discoveryService = discoveryService;
        this.secretStore = secretStore;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<ConnectionResponse> listConnections() {
        return lifecycleService.list().stream().map(c -> ConnectionResponse.from(c, maskedSuffix(c))).toList();
    }

    @GetMapping("/{connectionId}")
    public ResponseEntity<ConnectionDetailResponse> getConnection(@PathVariable String connectionId) {
        Connection connection = lifecycleService.requireByConnectionId(connectionId);
        return ResponseEntity.ok(ConnectionDetailResponse.from(connection, maskedSuffix(connection)));
    }

    @PostMapping
    public ResponseEntity<ConnectionResponse> createConnection(
            @RequestBody CreateConnectionRequest request) {
        Connection connection = lifecycleService.create(
                ConnectionKind.fromCode(request.kind()), request.name(),
                request.config() == null ? Map.of() : request.config(),
                request.secret());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConnectionResponse.from(connection, maskedSuffix(connection)));
    }
    @PatchMapping("/{connectionId}")
    public ResponseEntity<ConnectionDetailResponse> updateConnection(
            @PathVariable String connectionId,
            @RequestBody(required = false) JsonNode body) {
        if (body == null || body.isNull() || !body.isObject()) {
            throw new ConnectionValidationException("Update body must be a JSON object");
        }
        if (body.has("kind")) {
            throw new ConnectionValidationException("Connection kind is immutable");
        }
        boolean hasName = body.has("name");
        boolean hasConfig = body.has("config");
        boolean hasSecret = body.has("secret");
        if (!hasName && !hasConfig && !hasSecret) {
            throw new ConnectionValidationException("Update must change at least one of name, config, secret");
        }
        String name = null;
        if (hasName) {
            JsonNode node = body.get("name");
            if (node.isNull() || !node.isTextual()) {
                throw new ConnectionValidationException("Connection name must be a string");
            }
            name = node.asText();
        }
        Map<String, Object> config = null;
        if (hasConfig) {
            JsonNode node = body.get("config");
            if (node.isNull() || !node.isObject()) {
                throw new ConnectionValidationException("Connection config must be an object");
            }
            try {
                config = objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() { });
            } catch (IllegalArgumentException ex) {
                throw new ConnectionValidationException("Connection config must be an object");
            }
        }
        String secret = null;
        if (hasSecret) {
            JsonNode node = body.get("secret");
            if (node.isNull() || !node.isTextual() || node.asText().isBlank()) {
                throw new ConnectionValidationException("Replacement secret must be non-blank");
            }
            secret = node.asText();
        }
        Connection updated = lifecycleService.updateByConnectionId(
                connectionId, name, config, hasName, hasConfig, hasSecret, secret);
        return ResponseEntity.ok(ConnectionDetailResponse.from(updated, maskedSuffix(updated)));
    }

    @PostMapping("/{connectionId}/test")
    public ResponseEntity<DiscoveryResponse> test(@PathVariable String connectionId) {
        McpDiscovery discovery = lifecycleService.testByConnectionId(connectionId);
        return ResponseEntity.ok(DiscoveryResponse.from(discovery));
    }

    @PostMapping("/{connectionId}/connect")
    public ResponseEntity<DiscoveryResponse> connect(@PathVariable String connectionId) {
        McpDiscovery discovery = lifecycleService.connectByConnectionId(connectionId);
        return ResponseEntity.ok(DiscoveryResponse.from(discovery));
    }

    @PostMapping("/{connectionId}/refresh")
    public ResponseEntity<DiscoveryResponse> refresh(@PathVariable String connectionId) {
        McpDiscovery discovery = lifecycleService.refreshByConnectionId(connectionId);
        return ResponseEntity.ok(DiscoveryResponse.from(discovery));
    }

    @PostMapping("/{connectionId}/enable")
    public ResponseEntity<Void> enable(@PathVariable String connectionId) {
        lifecycleService.enableByConnectionId(connectionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{connectionId}/disable")
    public ResponseEntity<Void> disable(@PathVariable String connectionId) {
        lifecycleService.disableByConnectionId(connectionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{connectionId}")
    public ResponseEntity<Void> delete(@PathVariable String connectionId) {
        lifecycleService.deleteByConnectionId(connectionId);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/{connectionId}/tools")
    public List<ToolView> listTools(@PathVariable String connectionId) {
        Connection connection = lifecycleService.requireByConnectionId(connectionId);
        return discoveryService.findCached(connection.id())
                .map(discovery -> discovery.tools().stream()
                        .map(tool -> new ToolView(tool.name(), tool.description(), tool.inputSchema(), tool.annotations()))
                        .toList())
                .orElse(List.of());
    }

    @GetMapping("/{connectionId}/resources")
    public ResponseEntity<List<McpResource>> listResources(@PathVariable String connectionId) {
        Connection connection = lifecycleService.requireByConnectionId(connectionId);
        return ResponseEntity.ok(resourceProvider.discoverResources(connection));
    }

    @GetMapping("/{connectionId}/prompts")
    public ResponseEntity<List<McpPrompt>> listPrompts(@PathVariable String connectionId) {
        Connection connection = lifecycleService.requireByConnectionId(connectionId);
        return ResponseEntity.ok(promptAssetProvider.discoverPromptsResolved(connection));
    }

    @GetMapping("/{connectionId}/resources/read")
    public ResponseEntity<Map<String, Object>> readResource(
            @PathVariable String connectionId,
            @RequestParam("uri") String uri) {
        Connection connection = lifecycleService.requireByConnectionId(connectionId);
        var content = resourceProvider.readResolved(connection, uri);
        return ResponseEntity.ok(Map.of(
                "uri", content.uri(),
                "text", content.text(),
                "mimeType", content.mimeType(),
                "provenance", content.provenance()));
    }

    private String maskedSuffix(Connection connection) {
        if (connection.credentialRef() == null || connection.credentialRef().isBlank()) {
            return null;
        }
        return secretStore.maskedSuffix(connection.credentialRef());
    }

    // ---- DTOs ------------------------------------------------------------

    public record ConnectionResponse(String connectionId, String name, String kind,
                                     String status, boolean enabled, Map<String, Object> config,
                                     boolean hasCredential, String maskedSuffix, Instant createdAt, Instant updatedAt) {
        static ConnectionResponse from(Connection connection, String maskedSuffix) {
            Map<String, Object> safeConfig = connection.config() == null ? Map.of() : Map.copyOf(connection.config());
            boolean hasCredential = connection.credentialRef() != null && !connection.credentialRef().isBlank();
            return new ConnectionResponse(connection.connectionId(), connection.name(),
                    connection.kind().code(), connection.status().code(),
                    connection.enabled(), safeConfig, hasCredential, maskedSuffix,
                    connection.createdAt(), connection.updatedAt());
        }
    }

    public record ConnectionDetailResponse(String connectionId, String name, String kind,
                                           String status, boolean enabled, Map<String, Object> config,
                                           boolean hasCredential, String maskedSuffix,
                                           String lastError, Instant createdAt, Instant updatedAt) {
        static ConnectionDetailResponse from(Connection connection, String maskedSuffix) {
            Map<String, Object> safeConfig = connection.config() == null ? Map.of() : Map.copyOf(connection.config());
            boolean hasCredential = connection.credentialRef() != null && !connection.credentialRef().isBlank();
            return new ConnectionDetailResponse(connection.connectionId(), connection.name(),
                    connection.kind().code(), connection.status().code(),
                    connection.enabled(), safeConfig, hasCredential,
                    maskedSuffix, connection.lastError(), connection.createdAt(), connection.updatedAt());
        }
    }

    public record ToolView(String name, String description,
                           Map<String, Object> inputSchema, Map<String, Object> annotations) {
    }

    public record DiscoveryResponse(String serverInfo, String protocolVersion,
                                    int toolCount, int resourceCount, int promptCount,
                                    List<String> toolNames, List<String> resourceUris) {
        static DiscoveryResponse from(McpDiscovery discovery) {
            return new DiscoveryResponse(discovery.serverInfo(), discovery.protocolVersion(),
                    discovery.tools().size(), discovery.resources().size(),
                    discovery.prompts().size(),
                    discovery.tools().stream()
                            .map(tool -> tool.name()).toList(),
                    discovery.resources().stream().map(McpResource::uri).toList());
        }
    }

    public record CreateConnectionRequest(String kind, String name,
                                          Map<String, Object> config, String secret) {
    }
}
