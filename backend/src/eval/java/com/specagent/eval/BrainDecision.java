package com.specagent.eval;

import java.util.Map;

/**
 * 文件名:BrainDecision.java
 *
 * 用途:脚本化 Brain 输出(B-fast 层)中的 DECISION 主动作——动作族
 * (actionFamily)加负载数据,替代生产模型在该边界处的真实决策。
 *
 * 协作:作为 {@link BrainScript} 的组成部分,由 B-fast 层注入执行链路。
 */
public record BrainDecision(String actionFamily, Map<String, Object> payload) {

    public BrainDecision {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
