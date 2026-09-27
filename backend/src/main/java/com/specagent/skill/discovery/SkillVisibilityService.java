package com.specagent.skill.discovery;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件名:SkillVisibilityService.java
 *
 * 用途:<em>确定性</em> Skill 合格性(visibility)的唯一归属方:已安装 →
 * 已启用 → 与当前上下文的结构化事实兼容。它不做自然语言相关度排序 ——
 * 那是 {@link SkillCandidateRetriever} 的职责 —— 也不负责截断到模型可见的
 * Top-K 预算。截断由投影器负责,这样检索器总能看到完整的合格集合,未来
 * 的语义检索器才有机会召回第 24 名之后的候选。
 */
@Component
public class SkillVisibilityService {

    /**
     * 确定性的合格性过滤。当前规则:仅保留已启用的 Skill;资源类型兼容性
 * 提示由调用方(检索器/投影器层)按需解析。返回全部合格 Skill、不做上限
 * 截断 —— Top-K 与模型可见上限归检索器 + 投影器所有。
     */
    public List<SkillCatalogEntry> eligible(List<SkillCatalogEntry> all,
                                           SkillDiscoveryContext context) {
        return all.stream()
                .filter(SkillCatalogEntry::enabled)
                .toList();
    }
}