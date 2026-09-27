package com.specagent.connection;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:Connection.java
 *
 * 用途:一条已保存的 Connection 记录,是外部集成在产品层面的表示,
 * 贯穿连接管理(增删改查、测试、连接、启停)的各层。
 *
 * 绝不携带明文密钥——只保存指向加密凭据存储的 {@code credentialRef}。
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