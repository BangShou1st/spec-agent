package com.specagent.access;

import java.util.EnumSet;
import java.util.Set;

/**
 * 文件名:ProjectRole.java
 *
 * 用途:项目角色(第二阶段 §5)。
 *
 * <ul>
 *   <li>MANAGER = 读取 + 写入 + 管理 + 凭证使用。</li>
 *   <li>EDITOR  = 读取 + 写入 + 凭证使用(回答、改画布、undo/redo、创建写入 Run)。</li>
 *   <li>VIEWER  = 只读。<b>不</b>含 PROJECT_WRITE,也不含 CREDENTIAL_USE。</li>
 * </ul>
 *
 * 已冻结决定:项目重命名属于 {@link Capability#PROJECT_MANAGE},EDITOR 不允许。
 */
public enum ProjectRole {
    MANAGER(EnumSet.of(Capability.PROJECT_READ, Capability.PROJECT_WRITE,
            Capability.PROJECT_MANAGE, Capability.CREDENTIAL_USE)),
    EDITOR(EnumSet.of(Capability.PROJECT_READ, Capability.PROJECT_WRITE,
            Capability.CREDENTIAL_USE)),
    VIEWER(EnumSet.of(Capability.PROJECT_READ));

    private final Set<Capability> capabilities;

    ProjectRole(Set<Capability> capabilities) {
        this.capabilities = Set.copyOf(capabilities);
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }

    public boolean grants(Capability capability) {
        return capabilities.contains(capability);
    }

    public static ProjectRole parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("project role is required");
        }
        return ProjectRole.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
