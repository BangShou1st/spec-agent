package com.specagent.capability;

import java.util.List;
import java.util.Map;

/**
 * 文件名:CapabilityDescriptor.java
 *
 * 用途:单个能力暴露给规划器的有界描述,由运行时持有。描述符在呈现给模型前
 * 会经过权限过滤和上下文相关性筛选;规划器永远看不到实现类、SDK 客户端或凭据。
 *
 * @param capabilityId        能力唯一标识
 * @param version             能力版本
 * @param description         面向模型的能力说明
 * @param inputSchema         输入参数的 JSON Schema
 * @param outputSchema        输出结果的 JSON Schema
 * @param readOnly            是否只读(无外部副作用)
 * @param sideEffectClass     副作用分类
 * @param requiredPermissions 调用该能力所需的权限列表
 * @param supports            能力支持的附加声明
 */
public record CapabilityDescriptor(
        String capabilityId,
        String version,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        boolean readOnly,
        SideEffectClass sideEffectClass,
        List<String> requiredPermissions,
        List<String> supports) {

    public CapabilityDescriptor {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
        supports = supports == null ? List.of() : List.copyOf(supports);
    }
}
