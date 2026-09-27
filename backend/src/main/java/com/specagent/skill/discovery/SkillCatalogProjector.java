package com.specagent.skill.discovery;

import com.specagent.common.Hashes;
import com.specagent.skill.config.SkillProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件名:SkillCatalogProjector.java
 *
 * 用途:生成面向模型的、有界的 Skill 目录投影。输出一个小的、指纹稳定的
 * 条目列表,附带 {@code truncated} 标志与目录指纹,保证同一个冻结快照每次
 * 都能重放出完全相同的投影。
 */
@Component
public class SkillCatalogProjector {

    private final SkillProperties properties;

    public SkillCatalogProjector(SkillProperties properties) {
        this.properties = properties;
    }

    public Projection project(List<SkillCatalogEntry> entries, boolean truncated) {
        List<SkillCatalogEntry> bounded = entries.stream()
                .map(entry -> boundMetadata(entry))
                .limit(properties.getMaxVisible())
                .toList();
        String fingerprint = Hashes.sha256Hex(
                String.join("|", bounded.stream()
                        .map(e -> e.skillId() + "@" + e.contentHash())
                        .toList()));
        return new Projection(bounded, truncated || entries.size() > bounded.size(),
                fingerprint);
    }

    private SkillCatalogEntry boundMetadata(SkillCatalogEntry entry) {
        String description = entry.description();
        if (description != null
                && description.length() > properties.getMaxDescriptionChars()) {
            description = description.substring(0, properties.getMaxDescriptionChars()) + "…";
        }
        String hint = entry.compatibilityHint();
        if (hint != null && hint.length() > 120) {
            hint = hint.substring(0, 120) + "…";
        }
        return new SkillCatalogEntry(entry.skillId(), entry.name(), description,
                entry.versionId(), entry.contentHash(), entry.enabled(),
                entry.sourceKind(), hint);
    }

    public record Projection(List<SkillCatalogEntry> entries,
                             boolean truncated,
                             String fingerprint) {
    }
}