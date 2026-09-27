package com.specagent.eval;

import java.util.List;
import java.util.Map;

/**
 * 文件名:CapabilitySpec.java
 *
 * 用途:声明运行器为场景注册的一个能力(capability)的测试适配器:
 * 能力 ID、副作用类别、是否成功以及返回内容。{@code canonical()} 生成排序后
 * 的规范化字符串,供分层校验比对。
 *
 * 协作:由 {@link ScenarioDefinition} 声明,运行器据此注册桩能力。
 */
public record CapabilitySpec(
        String capabilityId,
        String sideEffectClass,
        boolean succeed,
        Map<String, Object> resultContent) {

    public CapabilitySpec {
        resultContent = resultContent == null ? Map.of() : Map.copyOf(resultContent);
    }

    public static String canonical(List<CapabilitySpec> capabilities) {
        return "caps" + capabilities.stream()
                .map(spec -> "cap(" + spec.capabilityId() + ","
                        + spec.sideEffectClass() + "," + spec.succeed() + ")")
                .sorted()
                .toList();
    }
}
