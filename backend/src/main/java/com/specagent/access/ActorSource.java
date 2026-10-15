package com.specagent.access;

/**
 * 文件名:ActorSource.java
 *
 * 用途:主体上下文的来源标记。授权判定不区分来源高低,但审计与
 * "不得提升权限"的检查必须能区分它们。
 *
 * <ul>
 *   <li>{@link #LOCAL}  本地单机模式的显式本地用户。</li>
 *   <li>{@link #OIDC}   经外部身份系统确认的登录用户。</li>
 *   <li>{@link #SYSTEM} 系统派生任务(如后台索引):窄服务权限,只能操作
 *       已登记的来源/产物。无 user 字段。</li>
 *   <li>{@link #SERVICE} 受信任内部服务调用(内部 token 已验证)。
 *       它只说明"请求来自受信任服务",绝不单独构成业务授权。</li>
 * </ul>
 */
public enum ActorSource {
    LOCAL,
    OIDC,
    SYSTEM,
    SERVICE;

    /** 是否为用户委托主体(只有用户主体携带 user 字段)。 */
    public boolean isUser() {
        return this == LOCAL || this == OIDC;
    }

    /**
     * 从持久化事实解析来源。**未知值一律拒绝**(失败关闭),绝不静默回落
     * 到某个默认来源——否则一个拼写错误就会把主体降级成本地或系统权限。
     */
    public static ActorSource fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("ActorSource code is required");
        }
        for (ActorSource source : values()) {
            if (source.name().equals(code)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown ActorSource code: " + code);
    }
}
