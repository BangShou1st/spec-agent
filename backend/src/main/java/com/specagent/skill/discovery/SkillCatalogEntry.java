package com.specagent.skill.discovery;

import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillVersion;

/**
 * 文件名:SkillCatalogEntry.java
 *
 * 用途:面向模型的单个 Skill 目录条目(有界视图)。Brain 只读取这种小
 * 条目 —— 永远接触不到文件系统路径、数据库内部结构、向量分数或完整包内容。
 */
public record SkillCatalogEntry(
        String skillId,
        String name,
        String description,
        String versionId,
        String contentHash,
        boolean enabled,
        String sourceKind,
        String compatibilityHint) {

    public static SkillCatalogEntry from(Skill skill, SkillVersion version) {
        return new SkillCatalogEntry(
                skill.skillId(),
                skill.name(),
                skill.description(),
                version == null ? null : version.id().toString(),
                version == null ? null : version.contentHash(),
                skill.enabled(),
                skill.sourceKind().code(),
                null);
    }

    public SkillCatalogEntry withCompatibilityHint(String hint) {
        return new SkillCatalogEntry(skillId, name, description, versionId, contentHash,
                enabled, sourceKind, hint);
    }
}