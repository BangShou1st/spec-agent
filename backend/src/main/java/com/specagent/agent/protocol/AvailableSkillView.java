package com.specagent.agent.protocol;

import java.util.List;

/**
 * 文件名:AvailableSkillView.java
 *
 * 用途:冻结输入快照中一条有界的、面向模型的 Skill 目录条目。
 *
 * 约束:只镜像发现投影中的身份与有界元数据——绝不包含完整
 * SKILL.md、文件系统路径、embedding 分数或数据库内部细节。
 */
public record AvailableSkillView(String skillId,
                                 String name,
                                 String description,
                                 String compatibilityHint) {

    public AvailableSkillView {
        description = description == null ? "" : description;
    }
}
