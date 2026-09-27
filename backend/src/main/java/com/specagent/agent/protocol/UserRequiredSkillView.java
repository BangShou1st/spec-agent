package com.specagent.agent.protocol;

import java.util.Locale;

/**
 * 文件名:UserRequiredSkillView.java
 *
 * 用途:用户通过 "/" Skill 选择器显式绑定到某个图谱节点的 Skill。
 *
 * 约束:与目录条目(模型自主选择)不同,这是用户指令——决策周期
 * 必须先激活它再做其他工作。构建器只有在绑定的 skill id 存在于已发现的
 * 启用目录中时才会设置该字段,因此被禁用或已移除的 Skill 绝不会到达模型。
 */
public record UserRequiredSkillView(String skillId, String name) {

    public UserRequiredSkillView {
        skillId = skillId == null ? "" : skillId.strip().toLowerCase(Locale.ROOT);
        name = name == null ? "" : name;
    }
}
