package com.specagent.skill.domain;

import java.util.List;
import java.util.Map;

/**
 * 文件名:SkillManifest.java
 *
 * 用途:Skill 包解析出的运行时元数据。开放 Agent Skills 规范的
 * {@code SKILL.md} YAML front-matter 是包格式的权威定义;其中仅
 * {@code name} 与 {@code description} 为必填。其余运行时元数据(skillId、
 * 来源、哈希、启用状态)归运行时存储所有,不属于包格式。
 */
public record SkillManifest(
        String name,
        String description,
        String instructions,
        Map<String, String> extraMetadata,
        List<String> references) {

    public static final String MINIMAL_NAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$";

    public SkillManifest {
        extraMetadata = extraMetadata == null ? Map.of() : Map.copyOf(extraMetadata);
        references = references == null ? List.of() : List.copyOf(references);
    }

    public boolean hasValidName() {
        return name != null && name.matches(MINIMAL_NAME_PATTERN);
    }
}