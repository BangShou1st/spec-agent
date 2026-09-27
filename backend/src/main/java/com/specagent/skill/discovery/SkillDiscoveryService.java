package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillVersion;
import com.specagent.skill.registry.SkillQueryService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:SkillDiscoveryService.java
 *
 * 用途:面向 Agent 的 Skill 发现门面。构建上下文时只经由这一个入口,
 * Agent 代码无需感知背后用的是哪种可见性/检索/投影实现。
 *
 * 发现按每个新的 Decision 上下文运行:同一个冻结快照会重放出完全相同的
 * 投影(目录由不可变的"已安装版本"状态与冻结输入推导而来),而新的续跑
 * 快照得到不同的目录也是合理的。
 */
@Service
public class SkillDiscoveryService {

    private final SkillQueryService queryService;
    private final SkillVisibilityService visibilityService;
    private final SkillCandidateRetriever retriever;
    private final SkillCatalogProjector projector;
    private final SkillProperties properties;

    public SkillDiscoveryService(SkillQueryService queryService,
                                 SkillVisibilityService visibilityService,
                                 SkillCandidateRetriever retriever,
                                 SkillCatalogProjector projector,
                                 SkillProperties properties) {
        this.queryService = queryService;
        this.visibilityService = visibilityService;
        this.retriever = retriever;
        this.projector = projector;
        this.properties = properties;
    }

    /**
     * 为一次发现上下文构建面向模型的、有界的 Skill 目录。全部合格候选进入
 * 检索器;模型可见的 Top-K 上限由投影器负责,因此 {@code truncated} 反映的
 * 是"合格 vs 投影",而非"已安装 vs 可见"。
     */
    public SkillCatalogProjector.Projection discover(SkillDiscoveryContext context) {
        List<SkillCatalogEntry> all = queryService.listSkills().stream()
                .map(this::toCatalogEntry)
                .toList();
        List<SkillCatalogEntry> eligible = visibilityService.eligible(all, context);
        List<SkillCatalogEntry> topK = retriever.retrieve(context, eligible,
                properties.getMaxVisible());
        boolean truncated = eligible.size() > topK.size();
        return projector.project(topK, truncated);
    }

    /**
     * {@code skill.search}:在完整合格集合上做带查询词的元数据检索 —— 回退路径
 * 的意义就在于召回自动 Top-K 没有展示的 Skill。它绝不激活任何东西,激活
 * 决定权在模型。只返回元数据(不含 SKILL.md 正文、资源内容、脚本、路径、
 * 数据库内部信息)。
     */
    public List<SkillSearchCandidate> search(SkillDiscoveryContext context) {
        List<SkillCatalogEntry> all = queryService.listSkills().stream()
                .map(this::toCatalogEntry)
                .toList();
        List<SkillCatalogEntry> eligible = visibilityService.eligible(all, context);
        List<SkillCatalogEntry> ranked = retriever.retrieve(context, eligible,
                properties.getSearchMaxResults());
        return ranked.stream()
                .map(entry -> new SkillSearchCandidate(
                        entry.skillId(), entry.name(), entry.description(),
                        entry.compatibilityHint()))
                .toList();
    }

    private SkillCatalogEntry toCatalogEntry(Skill skill) {
        Optional<SkillVersion> version = skill.currentVersionId() == null
                ? Optional.empty() : queryService.findVersion(skill.currentVersionId());
        SkillCatalogEntry entry = SkillCatalogEntry.from(skill, version.orElse(null));
        // 兼容性提示来自包声明的资源类型(metadata 引用),无需加载完整内容即可解析。
        String hint = compatibilityHint(entry);
        return hint == null ? entry : entry.withCompatibilityHint(hint);
    }

    private String compatibilityHint(SkillCatalogEntry entry) {
        if (entry.versionId() == null) {
            return null;
        }
        List<String> refs = queryService.listFileSummaries(UUID.fromString(entry.versionId()))
                .stream()
                .map(summary -> summary.relativePath())
                // SKILL.md 本身是指令正文,不构成提示;只有同级的资源/参考文件才描述兼容性。
                .filter(path -> !"SKILL.md".equals(path))
                .filter(path -> path.toLowerCase()
                        .matches(".*\\.(pdf|docx|xlsx|csv|json|sql|md|txt)$"))
                .limit(4)
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .toList();
        return refs.isEmpty() ? null : String.join(", ", refs);
    }
}