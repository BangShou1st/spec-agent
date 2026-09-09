package com.specagent.connection.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A saved Connection: the product-level representation of an external
 * integration. Never carries a plaintext secret — only a {@code credentialRef}
 * into the encrypted credential store.
 */
public record Connection(
        UUID id,
        String connectionId,
        String name,
        ConnectionKind kind,
        ConnectionStatus status,
        boolean enabled,
        Map<String, Object> config,
        String credentialRef,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public Connection {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public boolean agentVisible() {
        return enabled && status.agentVisible();
    }
}