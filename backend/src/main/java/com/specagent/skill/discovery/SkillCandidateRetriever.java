package com.specagent.skill.discovery;

import java.util.List;

/**
 * 文件名:SkillCandidateRetriever.java
 *
 * 用途:Skill 发现流程中候选集的语义级缩减/排序接口。首个实现是小目录下
 * 的直通实现;后续可替换的实现(词法/语义检索)仍然落在这一窄接口之后。
 * 它绝不授予权限、不激活 Skill、不做任何写操作 —— 最终的语义选择权仍在
 * 模型手中。
 */
public interface SkillCandidateRetriever {

    /**
     * 把合格候选缩减为与查询最相关的有界 Top-K。相同输入必须产生相同输出
     * (实现需保持确定性)。
     */
    List<SkillCatalogEntry> retrieve(SkillDiscoveryContext context,
                                     List<SkillCatalogEntry> eligible,
                                     int limit);
}