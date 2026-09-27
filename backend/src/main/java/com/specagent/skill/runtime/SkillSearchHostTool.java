package com.specagent.skill.runtime;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.InternalCapabilityAdapter;
import com.specagent.capability.SideEffectClass;
import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.discovery.SkillDiscoveryContext;
import com.specagent.skill.discovery.SkillDiscoveryService;
import com.specagent.skill.discovery.SkillSearchCandidate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件名:SkillSearchHostTool.java
 *
 * 用途:Host Function Tool {@code skill.search}:Skill 目录被截断时的
 * 仅元数据回退检索。它只缩小候选范围 —— 绝不激活任何东西。模型做出最终的
 * 语义决定,然后显式调用 {@code skill.activate}。
 *
 * 可见性由快照构建方负责:只有当投影出的目录被截断时,这个工具才对模型
 * 可见。只读、NONE 副作用等级。
 */
@Component
public class SkillSearchHostTool implements InternalCapabilityAdapter {

    public static final String CAPABILITY_ID = "skill.search";

    private final SkillDiscoveryService discoveryService;
    private final SkillProperties properties;

    public SkillSearchHostTool(SkillDiscoveryService discoveryService,
                               SkillProperties properties) {
        this.discoveryService = discoveryService;
        this.properties = properties;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "在 Skill 目录被截断时按语义搜索候选 Skill（仅返回元数据，不激活）",
                Map.of("query", Map.of("type", "string", "required", true)),
                Map.of("candidates", Map.of("type", "array")),
                true,
                SideEffectClass.NONE,
                List.of(),
                List.of());
    }

    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        Object rawQuery = invocation.arguments().get("query");
        if (!(rawQuery instanceof String query) || query.isBlank()) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID,
                    "arguments.query must be a non-blank string");
        }
        String boundedQuery = query.length() > 512
                ? query.substring(0, 512) : query;
        // 模型自己的查询词经类型化的搜索上下文流入共享检索器 —— 搜索对完整
        // 合格集合排序,服务层已按 searchMaxResults 限界。
        List<SkillSearchCandidate> candidates = discoveryService
                .search(SkillDiscoveryContext.forSearch(boundedQuery));
        List<Map<String, Object>> views = candidates.stream().map(candidate -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("skillId", candidate.skillId());
            view.put("name", candidate.name());
            view.put("description", candidate.description());
            if (candidate.compatibilityHint() != null) {
                view.put("compatibilityHint", candidate.compatibilityHint());
            }
            return view;
        }).toList();
        return new CapabilityResult(
                invocation.invocationId(), invocation.invocationKey(), CAPABILITY_ID,
                CapabilityResult.Status.SUCCEEDED,
                Map.of("candidates", views),
                List.of(),
                Map.of("kind", "SKILL_SEARCH",
                        "resultCount", views.size()),
                List.of());
    }
}
