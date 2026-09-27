package com.specagent.agent.runtime;

import java.util.UUID;

/**
 * 文件名:LoopLinkage.java
 *
 * 用途:{@code AgentRun} 行上承载的自治续跑链路标识。
 *
 * {@code parentRunId} 指向其终止边界派生出本 run 的父 run;
 * {@code rootRunId} 指向链头,便于整链查询;{@code cycleIndex} 记录子代
 * 深度,链根为 0。续跑机制之前的 run 与外部触发的 run,三者均为 null
 * (惰性链路标识:null 按"自身即链根、位于第 0 轮"解读),因此既有创建
 * 路径不受影响。
 *
 * 仅表示链路关系,绝不是语义状态:目标、冲突或规划内容一律不得进入
 * 该 record。
 */
public record LoopLinkage(UUID parentRunId, UUID rootRunId, Integer cycleIndex) {

    /** 不属于任何续跑链的 run 使用的链路标识(全 null)。 */
    public static LoopLinkage none() {
        return new LoopLinkage(null, null, null);
    }
}
