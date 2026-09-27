package com.specagent.agent.protocol;

import java.util.List;
import java.util.Map;

/**
 * 文件名:CapabilityResultView.java
 *
 * 用途:一次已完成的能力调用,以保留出处(provenance)的观察形式
 * 暴露给后续决策周期。
 *
 * 约束:能力结果属于外部证据或生成的摘要——绝不是自动确认的
 * 图谱事实,它们自身永远不会直接进入 effective claims。
 */
public record CapabilityResultView(String invocationId,
                                   String capabilityId,
                                   String status,
                                   Map<String, Object> content,
                                   List<String> sourceRefs,
                                   Map<String, Object> provenance) {

    public CapabilityResultView {
        content = content == null ? Map.of() : Map.copyOf(content);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }
}
