package com.specagent.access.identity;

import com.specagent.access.AccessMode;
import com.specagent.access.IdentityModeProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文件名:SecurityModeAdmission.java
 *
 * 用途:数据库范围的身份模式准入(第二阶段 §11),与 P1 的
 * {@code ExecutionModeAdmission} 同构。
 *
 * 准入规则:
 * <ol>
 *   <li>数据库登记行必须与本实例配置的模式<b>一致</b>;不一致拒绝启动,
 *       并给出维护窗口补救指引(停全部实例 -> 显式改写登记行 -> 启动新舰队)。</li>
 *   <li>OIDC 模式必须有 issuer 配置。缺配置<b>拒绝启动</b>,绝不自动回退
 *       LOCAL——回退会把一个配置错误的多人部署静默变成无登录部署。</li>
 *   <li>安全协议版本不一致同样拒绝:混合版本的舰队无法对会话与 cookie
 *       语义达成一致。</li>
 * </ol>
 *
 * 测试豁免:{@code spec.agent.security.mode-admission-enabled=false}。
 * 缓存的 Spring 测试上下文可能在同一数据库上以不同模式装配,生产不得关闭。
 */
@Component
public class SecurityModeAdmission {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityModeAdmission.class);
    // v13 also guards MCP recorded results, prompt observations and issued source bindings.
    private static final int SUPPORTED_PROTOCOL_VERSION = 13;

    private final SecurityModeRepository modes;
    private final IdentityModeProperties properties;

    private volatile boolean admitted;

    public SecurityModeAdmission(SecurityModeRepository modes, IdentityModeProperties properties) {
        this.modes = modes;
        this.properties = properties;
    }

    @PostConstruct
    public void verifyOnStartup() {
        verify();
    }

    /**
     * 校验;成功后可重复调用(幂等)。
     *
     * 刻意不做"已准入就直接返回"的短路:准入结论依赖数据库登记行,而登记行
     * 是运维在维护窗口内改写的。保留可重入的校验让"模式分歧"在任何时刻都能
     * 被显式复验(运维脚本与测试都依赖这一点),而不是只在进程启动那一瞬间成立。
     */
    public synchronized void verify() {
        admitted = false;
        if (!properties.isModeAdmissionEnabled()) {
            LOG.warn("Security mode admission check disabled (spec.agent.security."
                    + "mode-admission-enabled=false) — test-only, never in production");
            admitted = true;
            return;
        }
        AccessMode local = properties.accessMode();
        if (local == AccessMode.OIDC) {
            String issuer = properties.getIssuerUri();
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalStateException(
                        "OIDC mode requires spec.agent.security.issuer-uri; refusing to start "
                                + "instead of silently degrading to the local mode");
            }
        }
        SecurityModeRepository.RegisteredMode registered = modes.find()
                .orElseThrow(() -> new IllegalStateException(
                        "security_mode registration row is missing (migration V59); "
                                + "the database is not ready for this build"));
        if (!registered.mode().equals(local.name())) {
            throw new IllegalStateException(String.format(
                    "Security mode admission refused: database is registered as %s but this "
                            + "instance is configured as %s. Maintenance window: stop every "
                            + "instance, run UPDATE security_mode SET mode='%s' WHERE id=1, "
                            + "then start the new fleet.",
                    registered.mode(), local.name(), local.name()));
        }
        if (registered.protocolVersion() != SUPPORTED_PROTOCOL_VERSION) {
            throw new IllegalStateException(String.format(
                    "Security protocol version mismatch: database=%d, this instance=%d",
                    registered.protocolVersion(), SUPPORTED_PROTOCOL_VERSION));
        }
        if (local == AccessMode.OIDC && !properties.getIssuerUri().equals(registered.issuerUri())) {
            throw new IllegalStateException(
                    "Security issuer admission refused: configured issuer differs from database registration; "
                            + "stop every instance before changing the registered issuer");
        }
        admitted = true;
        LOG.info("Security mode admitted: mode={} protocolVersion={}",
                registered.mode(), registered.protocolVersion());
    }

    public boolean admitted() {
        return admitted;
    }

    public int supportedProtocolVersion() {
        return SUPPORTED_PROTOCOL_VERSION;
    }
}
