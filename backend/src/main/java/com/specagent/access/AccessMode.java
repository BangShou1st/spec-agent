package com.specagent.access;

/**
 * 文件名:AccessMode.java
 *
 * 用途:身份模式的固定枚举(第二阶段 §11)。
 *
 * 语义约束:
 * <ul>
 *   <li>{@link #LOCAL} 单机本地模式:创建显式的本地 user 与 org,仅回环绑定。
 *       它<b>不是</b>匿名管理员——缺少身份上下文绝不会被解释成高权限。</li>
 *   <li>{@link #OIDC} 多人模式:由企业身份系统确认身份,禁止本地身份绕过登录。</li>
 * </ul>
 *
 * 模式在数据库范围登记({@code security_mode}),同库实例必须一致;
 * 缺配置时拒绝多人模式启动,<b>绝不自动回退 LOCAL</b>。
 */
public enum AccessMode {
    LOCAL,
    OIDC;

    /**
     * 解析配置值。未知值抛异常而不是回退——回退会让一个拼错配置的
     * 多人部署静默降级为无登录模式。
     */
    public static AccessMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "spec.agent.security.mode must be explicitly LOCAL or OIDC");
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "LOCAL" -> LOCAL;
            case "OIDC" -> OIDC;
            default -> throw new IllegalArgumentException(
                    "Unsupported spec.agent.security.mode: " + raw);
        };
    }
}
