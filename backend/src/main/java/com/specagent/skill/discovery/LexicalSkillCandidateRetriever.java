package com.specagent.skill.discovery;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件名:LexicalSkillCandidateRetriever.java
 *
 * 用途:感知查询词的词法级候选检索器。当发现上下文携带搜索词时,按通用
 * 元数据相关度对全部候选排序并返回有界的 Top-K;没有搜索词时保持目录的
 * 稳定顺序(插入/注册顺序),供自动投影使用。
 *
 * 自动目录与 {@code skill.search} 的回退共用这一个检索抽象 —— 搜索功能
 * 不再另行实现排序。打分器对具体领域无感知(没有关键词表、同义词表、
 * 提供方路由),相关度就是纯粹的"查询词 ↔ 元数据"词元亲和度。
 */
@Component
public class LexicalSkillCandidateRetriever implements SkillCandidateRetriever {

    @Override
    public List<SkillCatalogEntry> retrieve(SkillDiscoveryContext context,
                                            List<SkillCatalogEntry> eligible,
                                            int limit) {
        if (eligible.isEmpty() || limit <= 0) {
            return limit <= 0 ? List.of() : List.copyOf(eligible);
        }
        String query = context == null ? null : context.searchQuery();
        List<SkillCatalogEntry> ordered = (query == null || query.isBlank())
                ? List.copyOf(eligible)
                : SkillMetadataScorer.rankByQuery(query, eligible);
        return ordered.stream().limit(limit).toList();
    }
}
