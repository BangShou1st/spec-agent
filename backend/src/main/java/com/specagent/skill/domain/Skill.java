package com.specagent.skill.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:Skill.java
 *
 * 用途:表示一个已安装的 Skill。{@code skillId} 是稳定的外部标识,升级
 * 过程中永不改变;{@code currentVersionId} 指向当前安装的不可变版本。
 */
public record Skill(
        UUID id,
        String skillId,
        String name,
        String description,
        SkillSourceKind sourceKind,
        String sourceIdentity,
        UUID currentVersionId,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}