package com.specagent.skill.discovery;

import com.specagent.skill.domain.Skill;
import com.specagent.skill.registry.SkillQueryService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 文件名:SkillHostToolVisibility.java
 *
 * 用途:决定 Skill 相关的 Host Function Tool({@code skill.activate}、
 * {@code skill.read_resource})在给定快照下是否对模型可见。
 *
 * 这两个工具返回的是过程性知识,只有项目确实存在"已安装且已启用"的
 * Skill 可激活时才有用。在所有上下文里一律可见会违反"installed != loaded"
 * 不变量,并用模型根本用不上的工具污染每一次决策输入。这里的门控事实完全
 * 确定:"本项目是否存在已启用的 Skill?" —— 永远不参考用户措辞。
 */
@Service
public class SkillHostToolVisibility {

    private final SkillQueryService queryService;

    public SkillHostToolVisibility(SkillQueryService queryService) {
        this.queryService = queryService;
    }

    public boolean anyEnabledSkill(UUID projectId) {
        if (projectId == null) {
            return false;
        }
        return queryService.listSkills().stream()
                .anyMatch(Skill::enabled);
    }
}