package com.specagent.skill.discovery;

import java.util.List;

/**
 * 文件名:SkillSearchCandidate.java
 *
 * 用途:语义检索搜索({@code skill.search})返回的单个候选元数据。只含
 * 元数据 —— 绝不携带完整 SKILL.md 内容。
 */
public record SkillSearchCandidate(
        String skillId,
        String name,
        String description,
        String compatibilityHint) {
}