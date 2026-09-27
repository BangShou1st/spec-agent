package com.specagent.capability;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:CapabilityQueryContext.java
 *
 * 用途:提供给 Provider 与可见性决策使用的结构化、确定性输入。
 *
 * 这里只承载结构化事实:已授予的权限、相关性过滤可匹配的上下文节点类型
 * ("KIND" 或 "KIND:SUBTYPE"),以及有界的范围事实(如用户显式选择、连接可用性)。
 * 这个 record 绝不嵌入自然语言语义——语义相关性是模型或检索阶段的工作,
 * 不是过滤器的输入。
 *
 * @param grantedPermissions 已授予的权限集合
 * @param contextKinds       上下文节点类型列表
 * @param scopeFacts         有界的范围事实
 */
public record CapabilityQueryContext(
        Set<String> grantedPermissions,
        List<String> contextKinds,
        Map<String, Object> scopeFacts) {

    public CapabilityQueryContext {
        grantedPermissions = grantedPermissions == null
                ? Set.of() : Set.copyOf(grantedPermissions);
        contextKinds = contextKinds == null ? List.of() : List.copyOf(contextKinds);
        scopeFacts = scopeFacts == null ? Map.of() : Map.copyOf(scopeFacts);
    }

    /** 无任何权限、上下文节点和范围事实的空上下文。 */
    public static CapabilityQueryContext empty() {
        return new CapabilityQueryContext(Set.of(), List.of(), Map.of());
    }

    /** 仅按权限约束的上下文(不含相关性范围)。 */
    public static CapabilityQueryContext forPermissions(Set<String> grantedPermissions) {
        return new CapabilityQueryContext(grantedPermissions, List.of(), Map.of());
    }

    public boolean hasGrant(String permission) {
        return grantedPermissions.contains(permission);
    }
}