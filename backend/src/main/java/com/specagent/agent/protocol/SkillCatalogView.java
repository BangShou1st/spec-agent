package com.specagent.agent.protocol;

/**
 * 文件名:SkillCatalogView.java
 *
 * 用途:冻结快照携带的有界 Skill 目录指纹,供回放(replay)校验
 * 模型当时看到的目录是否一致:包含稳定的条目身份、目录指纹以及
 * 截断标志。{@code userRequired} 在存在时携带用户针对当前上下文的
 * 显式 Skill 指令。
 */
public record SkillCatalogView(java.util.List<AvailableSkillView> skills,
                               boolean truncated,
                               String fingerprint,
                               UserRequiredSkillView userRequired) {

    public SkillCatalogView {
        skills = skills == null ? java.util.List.of() : java.util.List.copyOf(skills);
        fingerprint = fingerprint == null ? "" : fingerprint;
    }

    /** 兼容旧调用的构造器:适用于用户指令字段出现之前的调用方。 */
    public SkillCatalogView(java.util.List<AvailableSkillView> skills,
                            boolean truncated,
                            String fingerprint) {
        this(skills, truncated, fingerprint, null);
    }

    public static SkillCatalogView empty() {
        return new SkillCatalogView(java.util.List.of(), false, "", null);
    }
}
