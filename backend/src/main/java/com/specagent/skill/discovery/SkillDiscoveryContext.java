package com.specagent.skill.discovery;

import java.util.List;
import java.util.Map;

/**
 * 文件名:SkillDiscoveryContext.java
 *
 * 用途:由冻结的模型上下文推导出的、确定性的发现输入。这里只放结构化事实
 * (绝不放用户的原始措辞 —— 那会诱导词法路由):当前目标/操作提示、上下文中
 * 已出现的资源类型、有界的近期能力观察记录。
 *
 * 可选的 {@code searchQuery} 只承载模型显式编写的搜索词,仅供
 * {@code skill.search} 回退路径使用。运行时绝不会用环境中的用户文本填充它
 * —— 只有模型自己调用搜索工具的参数才会流到这里 —— 以此保证通用的词元
 * 匹配不会变成变相的关键词路由。
 */
public record SkillDiscoveryContext(
        String operation,
        List<String> resourceKinds,
        List<String> recentCapabilityIds,
        Map<String, Object> scopeFacts,
        String searchQuery) {

    public SkillDiscoveryContext {
        resourceKinds = resourceKinds == null ? List.of() : List.copyOf(resourceKinds);
        recentCapabilityIds = recentCapabilityIds == null
                ? List.of() : List.copyOf(recentCapabilityIds);
        scopeFacts = scopeFacts == null ? Map.of() : Map.copyOf(scopeFacts);
    }

    /** 兼容旧调用方的构造器:未显式给出搜索词的场景使用。 */
    public SkillDiscoveryContext(String operation,
                                 List<String> resourceKinds,
                                 List<String> recentCapabilityIds,
                                 Map<String, Object> scopeFacts) {
        this(operation, resourceKinds, recentCapabilityIds, scopeFacts, null);
    }

    public static SkillDiscoveryContext empty() {
        return new SkillDiscoveryContext(null, List.of(), List.of(), Map.of());
    }

    /** 回退路径的显式搜索词;非搜索场景为 null。 */
    public static SkillDiscoveryContext forSearch(String query) {
        return new SkillDiscoveryContext(null, List.of(), List.of(), Map.of(), query);
    }
}