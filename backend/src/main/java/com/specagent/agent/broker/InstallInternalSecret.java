package com.specagent.agent.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 文件名:InstallInternalSecret.java
 *
 * 用途:安装实例独有的内部密钥管理。交付路径中不再存在仓库级固定
 * 开发密钥:配置显式提供 {@code spec.agent.brain.internal-secret} 时
 * 原样使用(测试/CI/高级部署);未提供时,首次启动生成 32 字节随机
 * 密钥并持久化到密钥文件(默认 {@code ./data/internal-secret.txt}),
 * 之后每次启动读取同一文件——后端与 Brain 从同一来源取值,两端一致。
 *
 * 文件缺失/损坏会在初始化时显式失败或重新生成;已有密文请求会因 token
 * 不一致而 401,绝不静默降级为无认证。
 */
@Component
public class InstallInternalSecret {

    private static final Logger LOG = LoggerFactory.getLogger(InstallInternalSecret.class);

    private final AgentBrainProperties properties;
    private final Path secretFile;

    public InstallInternalSecret(AgentBrainProperties properties,
                                 @Value("${spec.agent.brain.internal-secret-file:./data/internal-secret.txt}")
                                 String secretFile) {
        this.properties = properties;
        this.secretFile = Path.of(secretFile);
        ensureSecret();
    }

    /** 配置为空时生成并持久化安装实例密钥;两端消费方都读取同一属性。 */
    private void ensureSecret() {
        if (properties.getInternalSecret() != null && !properties.getInternalSecret().isBlank()) {
            return;
        }
        try {
            if (Files.exists(secretFile)) {
                String stored = Files.readString(secretFile, StandardCharsets.UTF_8).strip();
                if (!stored.isEmpty()) {
                    properties.setInternalSecret(stored);
                    return;
                }
            }
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String generated = HexFormat.of().formatHex(raw);
            Path parent = secretFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(secretFile, generated, StandardCharsets.UTF_8);
            restrictPermissionsBestEffort();
            properties.setInternalSecret(generated);
            LOG.info("Generated per-install internal secret at {}", secretFile.toAbsolutePath());
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Internal secret is neither configured nor can the secret file "
                            + secretFile + " be read or created: " + ex.getMessage(), ex);
        }
    }

    private void restrictPermissionsBestEffort() {
        try {
            Files.setPosixFilePermissions(secretFile, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Windows / 非 POSIX 文件系统没有 POSIX 权限;密钥文件继承目录 ACL。
        }
    }
}
