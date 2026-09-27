package com.specagent.agent.broker;

import java.util.UUID;

/**
 * 文件名:RunExistenceCheck.java
 *
 * 用途:端口接口,供内部推理 broker 在处理请求前校验 run id 是否真实
 * 存在。实现位于 runtime 包、委托给持久化存储,使 broker 不依赖任何
 * 仓库(repository)。
 *
 * 协作:由 InternalModelInferenceController 的契约校验阶段调用。
 */
@FunctionalInterface
public interface RunExistenceCheck {

    /**
     * 当给定 run id 对应一条真实、已持久化的 AgentRun 记录时返回 {@code true}。
     */
    boolean exists(UUID runId);
}
