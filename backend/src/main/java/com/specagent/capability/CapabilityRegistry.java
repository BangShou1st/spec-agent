package com.specagent.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 文件名:CapabilityRegistry.java
 *
 * 用途:宿主运行时的能力注册表,负责能力发现、权限过滤和适配器/Provider 路由。
 *
 * 注册表是宿主侧唯一的目录,刻意让两类能力共存:
 *
 * - 【静态适配器】——传统 {@link CapabilityAdapter} 实例(宿主 Function Tools
 *       和内置能力),在构造时一次性注册;以及
 * - 【动态 Provider】——{@link CapabilityProvider} SPI 实例,其描述符集合可在
 *       运行期变化(MCP 工具发现),按上下文实时查询。 *
 * 描述符在呈现给模型前会按已授予的权限过滤——规划器只会看到项目/用户有权调用的
 * 能力,并且绝不依据实现类名做分支判断。两个来源中出现重复的 capability id 时按
 * "失败即关闭"(fail closed)处理:同一个 id 有两个属主会是歧义的。
 */
@Component
public class CapabilityRegistry {

    private final Map<String, CapabilityAdapter> adaptersById = new LinkedHashMap<>();
    private final List<CapabilityProvider> providers;

    /** 传统构造器:仅注册静态适配器(用于测试和简单装配)。 */
    public CapabilityRegistry(List<CapabilityAdapter> adapters) {
        this(adapters, List.of());
    }

    @Autowired
    public CapabilityRegistry(List<CapabilityAdapter> adapters,
                              List<CapabilityProvider> providers) {
        for (CapabilityAdapter adapter : adapters == null ? List.<CapabilityAdapter>of() : adapters) {
            String id = adapter.descriptor().capabilityId();
            if (adaptersById.put(id, adapter) != null) {
                throw new IllegalStateException("Duplicate capability id: " + id);
            }
        }
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /** 全部静态适配器的描述符(宿主侧视图,未过滤)。 */
    public Collection<CapabilityDescriptor> allDescriptors() {
        return adaptersById.values().stream().map(CapabilityAdapter::descriptor).toList();
    }

    /**
     * 空上下文下规划器可见的描述符:权限过滤后的静态 + 动态描述符。
     * 保留给旧调用点使用;需要上下文感知的视图请优先使用
     * {@link #descriptorsFor(CapabilityQueryContext)}。
     */
    public List<CapabilityDescriptor> descriptorsFor(Set<String> grantedPermissions) {
        return descriptorsFor(CapabilityQueryContext.forPermissions(grantedPermissions));
    }

    /**
     * 上下文感知的描述符视图:静态适配器加上每个 Provider 当前提供的描述符,
     * 经权限过滤、按 id 去重,出现重复即失败关闭。Provider 在返回描述符前
     * 已自行完成可用性过滤(启用/已连接)。
     */
    public List<CapabilityDescriptor> descriptorsFor(CapabilityQueryContext context) {
        Set<String> grants = context.grantedPermissions();
        Map<String, CapabilityDescriptor> byId = new LinkedHashMap<>();
        for (CapabilityAdapter adapter : adaptersById.values()) {
            CapabilityDescriptor descriptor = adapter.descriptor();
            if (grants.containsAll(descriptor.requiredPermissions())) {
                putUnique(byId, descriptor);
            }
        }
        for (CapabilityProvider provider : providers) {
            for (CapabilityDescriptor descriptor : provider.descriptorsFor(context)) {
                if (grants.containsAll(descriptor.requiredPermissions())) {
                    putUnique(byId, descriptor);
                }
            }
        }
        return List.copyOf(byId.values());
    }

    private void putUnique(Map<String, CapabilityDescriptor> byId,
                           CapabilityDescriptor descriptor) {
        String id = descriptor.capabilityId();
        if (byId.put(id, descriptor) != null) {
            throw new IllegalStateException(
                    "Duplicate capability id across providers/adapters: " + id);
        }
    }

    public Optional<CapabilityDescriptor> findDescriptor(String capabilityId) {
        CapabilityAdapter adapter = adaptersById.get(capabilityId);
        if (adapter != null) {
            return Optional.of(adapter.descriptor());
        }
        for (CapabilityProvider provider : providers) {
            if (provider.canHandle(capabilityId)) {
                Optional<CapabilityDescriptor> descriptor = provider.descriptorFor(capabilityId);
                if (descriptor.isPresent()) {
                    return descriptor;
                }
            }
        }
        return Optional.empty();
    }

    public Optional<CapabilityAdapter> findAdapter(String capabilityId) {
        CapabilityAdapter adapter = adaptersById.get(capabilityId);
        if (adapter != null) {
            return Optional.of(adapter);
        }
        for (CapabilityProvider provider : providers) {
            if (provider.canHandle(capabilityId)
                    && provider.descriptorFor(capabilityId).isPresent()) {
                return Optional.of(new ProviderBackedAdapter(provider, capabilityId));
            }
        }
        return Optional.empty();
    }

    /**
     * 把 Provider 拥有的能力桥接到传统适配器契约上,使执行/分发代码保持单一通路。
     * 描述符在每次查找时惰性解析,从而正确响应可用性变化。
     */
    private record ProviderBackedAdapter(CapabilityProvider provider, String capabilityId)
            implements CapabilityAdapter {

        @Override
        public CapabilityDescriptor descriptor() {
            return provider.descriptorFor(capabilityId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Provider capability became unavailable during dispatch: "
                                    + capabilityId));
        }

        @Override
        public CapabilityResult invoke(CapabilityInvocation invocation) {
            return provider.invoke(invocation);
        }
    }
}