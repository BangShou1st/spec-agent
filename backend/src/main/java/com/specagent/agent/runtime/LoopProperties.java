package com.specagent.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件名:LoopProperties.java
 *
 * 用途:自治续跑链的预算配置(对应 {@code spec.agent.loop} 前缀)。
 *
 * {@code maxCycles} 是一条自治续跑链允许的 AgentRun 总数,链根也计入。
 * 它只限制执行轮数,绝不判断模型何时"应该"停止推理。
 */
@Component
@ConfigurationProperties(prefix = "spec.agent.loop")
public class LoopProperties {

    /**
     * 每条链的 run 总数:链根(第 0 轮)加所有续跑。只要
     * {@code cycleIndex + 1 < maxCycles} 就恰好可以创建一个子 run,因此
     * 剩余预算由持久化的 cycle index 与本配置确定性推导——不需要额外存储
     * 剩余预算计数器。
     */
    private int maxCycles = 3;

    public int getMaxCycles() {
        return maxCycles;
    }

    public void setMaxCycles(int maxCycles) {
        if (maxCycles < 1) {
            throw new IllegalArgumentException(
                    "spec.agent.loop.max-cycles must be >= 1, got " + maxCycles);
        }
        this.maxCycles = maxCycles;
    }
}
