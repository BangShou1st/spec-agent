package com.specagent.agent.protocol;

import java.util.List;
import java.util.Map;

/**
 * 文件名:CapabilityDescriptor.java
 *
 * 用途:Brain 可以引用的能力描述符。Java 侧先按权限与上下文相关性
 * 过滤后再暴露给模型;描述符只携带有界元数据——绝不包含实现类、
 * 端点地址或凭证。
 *
 * 约束:{@code inputSchema} 与 {@code supports} 对线上(wire)与回放
 * 兼容均为可选:字段缺失(旧版冻结 payload、旧版 Brain)时按空集合
 * 往返,不会破坏严格解析。{@code inputSchema} 携带一个有界的
 * JSON-Schema 风格的参数形状,供模型为动态 provider(如 MCP 工具)
 * 构造合法调用;{@code supports} 镜像驱动其可见性的 Runtime 相关性事实
 * ("KIND" 或 "KIND:SUBTYPE" 形式)。
 */
public record CapabilityDescriptor(String id,
                                   String version,
                                   String description,
                                   Map<String, Object> inputSchema,
                                   boolean readOnly,
                                   String sideEffectClass,
                                   List<String> supports) {

    public CapabilityDescriptor {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        supports = supports == null ? List.of() : List.copyOf(supports);
    }

    /** 兼容旧调用的构造器:适用于有界 schema 字段出现之前的调用方。 */
    public CapabilityDescriptor(String id,
                                String version,
                                String description,
                                boolean readOnly,
                                String sideEffectClass) {
        this(id, version, description, Map.of(), readOnly, sideEffectClass, List.of());
    }
}
