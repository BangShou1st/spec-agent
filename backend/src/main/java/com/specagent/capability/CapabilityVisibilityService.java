package com.specagent.capability;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件名:CapabilityVisibilityService.java
 *
 * 用途:为面向规划器的能力目录做确定性的资格判定(哪些能力对模型可见)。
 *
 * {@code CapabilityVisibilityService} 只负责流水线中"过滤"这一半:上下文兼容性
 * (描述符的 {@code supports} 声明与上下文节点类型的匹配),加上有界投影
 * (最大可见数量 / 载荷大小上限)。它不做语义相关性排序,不授予权限
 * (权限过滤仍留在注册表),也不决定最终的语义动作——那个选择属于模型。
 *
 * 当目录规模扩大后,检索/排序可以作为独立的 {@code CapabilityCandidateRetriever}
 * 插在这个服务之后,由规模评估决定时机;确定性的资格判定与语义相关性
 * 永远不会被合并成一个"万能排序器"。
 */
@Component
public class CapabilityVisibilityService {

    private final CapabilityRegistry registry;
    private final CapabilityCatalogLimits limits;

    public CapabilityVisibilityService(CapabilityRegistry registry,
                                       CapabilityCatalogLimits limits) {
        this.registry = registry;
        this.limits = limits;
    }

    /**
     * 返回经权限过滤、上下文兼容且有界的能力描述符。Provider 只返回当前可用的
     * 描述符;注册表负责权限过滤;本服务负责 {@code supports} 与
     * {@code contextKinds} 的兼容性判断以及目录上限约束。
     */
    public List<CapabilityDescriptor> visibleCapabilities(CapabilityQueryContext context) {
        return registry.descriptorsFor(context).stream()
                .filter(descriptor -> supportsContext(descriptor, context.contextKinds()))
                .limit(limits.maxVisible())
                .map(this::boundDescriptor)
                .toList();
    }

    /**
     * 不依赖上下文的可见性视图,供无决策快照就列出目录的宿主工具使用
     * (例如管理界面)。
     */
    public List<CapabilityDescriptor> visibleCapabilities() {
        return visibleCapabilities(CapabilityQueryContext.empty());
    }

    private boolean supportsContext(CapabilityDescriptor descriptor,
                                    List<String> contextKinds) {
        if (descriptor.supports().isEmpty()) {
            return true;
        }
        return descriptor.supports().stream().anyMatch(support -> contextKinds.stream()
                .anyMatch(kind -> supportMatches(support, kind)));
    }

    private boolean supportMatches(String support, String contextKind) {
        int separator = support.indexOf(':');
        if (separator < 0) {
            return support.equalsIgnoreCase(contextKind);
        }
        String supportKind = support.substring(0, separator);
        String supportSubtype = support.substring(separator + 1);
        int kindSeparator = contextKind.indexOf(':');
        if (kindSeparator < 0) {
            return supportKind.equalsIgnoreCase(contextKind);
        }
        return supportKind.equalsIgnoreCase(contextKind.substring(0, kindSeparator))
                && supportSubtype.equalsIgnoreCase(contextKind.substring(kindSeparator + 1));
    }

    /** 面向模型的有界元数据副本;超长描述会被截断。 */
    private CapabilityDescriptor boundDescriptor(CapabilityDescriptor descriptor) {
        String description = descriptor.description();
        if (description.length() > limits.maxDescriptionChars()) {
            description = description.substring(0, limits.maxDescriptionChars()) + "…";
        }
        return new CapabilityDescriptor(
                descriptor.capabilityId(),
                descriptor.version(),
                description,
                descriptor.inputSchema(),
                descriptor.outputSchema(),
                descriptor.readOnly(),
                descriptor.sideEffectClass(),
                descriptor.requiredPermissions(),
                descriptor.supports());
    }
}