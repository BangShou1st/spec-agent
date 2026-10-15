package com.specagent.access;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件名:IdentityModeProperties.java
 *
 * 用途:身份模式的部署配置({@code spec.agent.security.*})。
 *
 * 全部值都是<b>实验初值</b>,只有测量之后才能成为承载承诺。
 *
 * 关键约束:
 * <ul>
 *   <li>{@code mode} 必须显式 LOCAL 或 OIDC。多人模式缺配置时拒绝启动,
 *       绝不自动回退 LOCAL。</li>
 *   <li>{@code modeAdmissionEnabled} 仅供测试(缓存的测试上下文可能在同一
 *       数据库上以不同模式装配);生产不得关闭。</li>
 *   <li>会话超时:空闲 30 分钟、绝对 8 小时,均为待评估参数。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "spec.agent.security")
public class IdentityModeProperties {

    /** LOCAL | OIDC。 */
    private String mode = "LOCAL";

    /** OIDC issuer(单配置 issuer;数据结构允许以后接多个)。 */
    private String issuerUri;

    /** 数据库范围的身份模式准入校验开关(测试豁免,生产必须为 true)。 */
    private boolean modeAdmissionEnabled = true;

    /** 空闲会话超时。 */
    private Duration idleTimeout = Duration.ofMinutes(30);

    /** 绝对登录期限。 */
    private Duration absoluteTimeout = Duration.ofHours(8);

    /** LOCAL 模式创建的显式组织名。 */
    private String localOrgName = "Local Workspace";

    /** LOCAL 模式创建的显式用户显示名。 */
    private String localUserName = "Local User";

    public AccessMode accessMode() {
        return AccessMode.parse(mode);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public boolean isModeAdmissionEnabled() {
        return modeAdmissionEnabled;
    }

    public void setModeAdmissionEnabled(boolean modeAdmissionEnabled) {
        this.modeAdmissionEnabled = modeAdmissionEnabled;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getAbsoluteTimeout() {
        return absoluteTimeout;
    }

    public void setAbsoluteTimeout(Duration absoluteTimeout) {
        this.absoluteTimeout = absoluteTimeout;
    }

    public String getLocalOrgName() {
        return localOrgName;
    }

    public void setLocalOrgName(String localOrgName) {
        this.localOrgName = localOrgName;
    }

    public String getLocalUserName() {
        return localUserName;
    }

    public void setLocalUserName(String localUserName) {
        this.localUserName = localUserName;
    }
}
