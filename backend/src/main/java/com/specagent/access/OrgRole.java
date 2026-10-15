package com.specagent.access;

import java.util.EnumSet;
import java.util.Set;

/**
 * 文件名:OrgRole.java
 *
 * 用途:组织角色(第二阶段 §5)。组织管理员有项目访问治理能力,可以显式为
 * 自己授予项目角色,但授予行为必须审计;管理角色<b>不</b>暗中读取项目正文。
 *
 * 注意:创建项目是<b>成员级</b>动作——三个角色都允许。它因此不是能力,
 * 而由"是否为有效组织成员"这一条前置条件承担。
 */
public enum OrgRole {
    OWNER(EnumSet.of(Capability.ORG_MANAGE, Capability.ORG_OWNER, Capability.CREDENTIAL_MANAGE)),
    ADMIN(EnumSet.of(Capability.ORG_MANAGE, Capability.CREDENTIAL_MANAGE)),
    MEMBER(EnumSet.noneOf(Capability.class));

    private final Set<Capability> capabilities;

    OrgRole(Set<Capability> capabilities) {
        this.capabilities = Set.copyOf(capabilities);
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }

    public boolean grants(Capability capability) {
        return capabilities.contains(capability);
    }

    public static OrgRole parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("organization role is required");
        }
        return OrgRole.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
