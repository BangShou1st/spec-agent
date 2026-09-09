package com.specagent.api.connection;

import com.specagent.connection.domain.Connection;
import com.specagent.connection.domain.ConnectionKind;
import com.specagent.connection.service.ConnectionCommandException;
import com.specagent.connection.service.ConnectionLifecycleService;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpPrompt;
import com.specagent.mcp.domain.McpResource;
import com.specagent.mcp.provider.McpPromptAssetProvider;
import com.specagent.mcp.provider.McpResourceProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Connection management backend API. Connection (product concept) and MCP
 * (protocol) stay separate in the model; the API exposes both facets. Auth
 * callbacks stay in the auth layer; this surface is the management contract
 * the future Connections UI consumes.
 *
 * <p>Secrets never appear in responses: only the masked suffix and the
 * credential ref are exposed.
 */
@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {

    private final ConnectionLifecycleService lifecycleService;
    private final McpResourceProvider resourceProvider;
    private final McpPromptAssetProvider promptAssetProvider;
    private final com.specagent.connection.credentials.SecretStore secretStore;

    public ConnectionController(ConnectionLifecycleService lifecycleService,
                                McpResourceProvider resourceProvider,
                                McpPromptAssetProvider promptAssetProvider,
                                com.specagent.connection.credentials.SecretStore secretStore) {
        this.lifecycleService = lifecycleService;
        this.resourceProvider = resourceProvider;
        this.promptAssetProvider = promptAssetProvider;
        this.secretStore = secretStore;
    }

    @GetMapping
    public List<ConnectionResponse> listConnections() {
        return lifecycleService.list().stream().map(ConnectionResponse::from).toList();
    }

    @GetMapping("/{connectionId}")
    public ResponseEntity<ConnectionDetailResponse> getConnection(@PathVariable String connectionId) {
        return lifecycleService.find(connectionId)
                .map(connection -> ResponseEntity.ok(ConnectionDetailResponse.from(
                        connection, maskedSuffix(connection))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ConnectionResponse> createConnection(
            @RequestBody CreateConnectionRequest request) {
        Connection connection = lifecycleService.create(
                ConnectionKind.fromCode(request.kind()), request.name(),
                request.config() == null ? Map.of() : request.config(),
                request.secret());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConnectionResponse.from(connection));
    }

    @PostMapping("/{connectionRowId}/test")
    public ResponseEntity<DiscoveryResponse> test(@PathVariable UUID connectionRowId) {
        McpDiscovery discovery = lifecycleService.test(connectionRowId);
        return ResponseEntity.ok(DiscoveryResponse.from(discovery));
    }

    @PostMapping("/{connectionRowId}/connect")
    public ResponseEntity<DiscoveryResponse> connect(@PathVariable UUID connectionRowId) {
        McpDiscovery discovery = lifecycleService.connect(connectionRowId);
        return ResponseEntity.ok(DiscoveryResponse.from(discovery));
    }

    @PostMapping("/{connectionRowId}/refresh")
    public ResponseEntity<DiscoveryResponse> refresh(@PathVariable UUID connectionRowId) {
        McpDiscovery discovery = lifecycleService.refresh(connectionRowId);
        return ResponseEntity.ok(DiscoveryResponse.from(discovery));
    }

    @PostMapping("/{connectionRowId}/enable")
    public ResponseEntity<Void> enable(@PathVariable UUID connectionRowId) {
        lifecycleService.enable(connectionRowId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{connectionRowId}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID connectionRowId) {
        lifecycleService.disable(connectionRowId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{connectionRowId}")
    public ResponseEntity<Void> delete(@PathVariable UUID connectionRowId) {
        lifecycleService.delete(connectionRowId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{connectionRowId}/resources")
    public ResponseEntity<List<McpResource>> listResources(@PathVariable UUID connectionRowId) {
        return lifecycleService.findByRowId(connectionRowId)
                .map(connection -> ResponseEntity.ok(
                        resourceProvider.discoverResources(connection)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{connectionRowId}/prompts")
    public ResponseEntity<List<McpPrompt>> listPrompts(@PathVariable UUID connectionRowId) {
        return lifecycleService.findByRowId(connectionRowId)
                .map(connection -> ResponseEntity.ok(
                        promptAssetProvider.discoverPrompts(connection.id())))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{connectionRowId}/resources/read")
    public ResponseEntity<Map<String, Object>> readResource(
            @PathVariable UUID connectionRowId,
            @RequestParam("uri") String uri) {
        var content = resourceProvider.read(connectionRowId, uri);
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
        // Only the masked suffix crosses the API boundary — the plaintext
        // secret never leaves SecretStore.
        return secretStore.maskedSuffix(connection.credentialRef());
    }

    // ---- DTOs ------------------------------------------------------------

    public record ConnectionResponse(String connectionId, String name, String kind,
                                     String status, boolean enabled, Instant createdAt) {
        static ConnectionResponse from(Connection connection) {
            return new ConnectionResponse(connection.connectionId(), connection.name(),
                    connection.kind().code(), connection.status().code(),
                    connection.enabled(), connection.createdAt());
        }
    }

    public record ConnectionDetailResponse(String connectionId, String name, String kind,
                                           String status, boolean enabled,
                                           String credentialRef, String maskedSuffix,
                                           String lastError, Instant createdAt) {
        static ConnectionDetailResponse from(Connection connection, String maskedSuffix) {
            return new ConnectionDetailResponse(connection.connectionId(), connection.name(),
                    connection.kind().code(), connection.status().code(),
                    connection.enabled(), connection.credentialRef(),
                    maskedSuffix, connection.lastError(), connection.createdAt());
        }
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