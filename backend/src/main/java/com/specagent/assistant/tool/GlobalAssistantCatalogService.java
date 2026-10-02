package com.specagent.assistant.tool;
import com.specagent.capability.CapabilityCatalogLimits;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.CapabilityRegistry;
import java.util.List;
import org.springframework.stereotype.Service;
/**
 * 文件名:GlobalAssistantCatalogService.java
 *
 * 用途:面向模型的 GA(全局助手)产品目录。解析顺序是刻意安排的:
 * 先走注册表的权限/供应商解析,再过 GA 允许 ID 过滤器,然后做精确的
 * GA 支持标记兼容检查,最后才做有界投影。这样无关能力永远不可能
 * 在全局截断窗口里把 V1 的四个工具挤掉。不建第二套注册表。
 */
@Service
public class GlobalAssistantCatalogService {
    private final CapabilityRegistry registry;
    private final CapabilityCatalogLimits limits;
    private final TavilyWebService web;
    private final GaRetrievalReadiness retrieval;
    public GlobalAssistantCatalogService(CapabilityRegistry registry, CapabilityCatalogLimits limits, TavilyWebService web, GaRetrievalReadiness retrieval) {
        this.registry = registry;
        this.web=web; this.retrieval=retrieval;
        this.limits = limits;
    }
    public List<CapabilityDescriptor> modelCatalog() {
        return registry.descriptorsFor(CapabilityQueryContext.empty()).stream()
                .filter(descriptor -> GlobalAssistantToolCatalog.isAllowed(descriptor.capabilityId()))
                .filter(descriptor -> descriptor.supports().contains(GlobalAssistantToolCatalog.SUPPORT_MARKER))
                .filter(descriptor -> !descriptor.capabilityId().startsWith("web.") || web.configured())
                .filter(descriptor -> !java.util.Set.of("help.search","project.content.discover").contains(descriptor.capabilityId()) || retrieval.ready())
                .map(this::bound)
                .limit(10)
                .toList();
    }
    private CapabilityDescriptor bound(CapabilityDescriptor descriptor) {
        String description = descriptor.description();
        if (description != null && description.length() > limits.maxDescriptionChars()) {
            description = description.substring(0, limits.maxDescriptionChars()) + "...";
        }
        return new CapabilityDescriptor(descriptor.capabilityId(), descriptor.version(), description,
                descriptor.inputSchema(), descriptor.outputSchema(), descriptor.readOnly(),
                descriptor.sideEffectClass(), descriptor.requiredPermissions(), descriptor.supports());
    }
}
