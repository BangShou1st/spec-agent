package com.specagent.agent.decision;

import com.specagent.agent.protocol.ModelContractException;

import java.util.Objects;

/**
 * 文件名:ModelResponseCorrelation.java
 *
 * 用途:运行时拥有的关联性(correlation)校验,核对 {@link ModelRequest}
 * 与网关为其返回的 {@link ModelResponse} 是否对应。
 *
 * 网关输出始终是不可信输入。在任何类型化解析、Reflection 或持久化发生
 * 之前,运行时必须先确认响应回显了与请求完全一致的 agentRunId、
 * contextSnapshotId 和 taskType。任何不匹配都会整体拒绝该响应:所在 run
 * 判为失败,且源自该响应的任何产物都不得持久化。
 */
public final class ModelResponseCorrelation {

    private ModelResponseCorrelation() {
    }

    /**
     * 校验响应确实是针对这一条请求产生的。
     *
     * @throws ModelContractException 存在任何关联字段不匹配时抛出
     */
    public static void validate(ModelRequest request, ModelResponse response) {
        if (!Objects.equals(response.requestAgentRunId(), request.agentRunId())) {
            throw new ModelContractException(
                    "Model response agentRunId does not match the request agentRunId");
        }
        if (!Objects.equals(response.requestContextSnapshotId(), request.contextSnapshotId())) {
            throw new ModelContractException(
                    "Model response contextSnapshotId does not match the request contextSnapshotId");
        }
        if (response.taskType() != request.taskType()) {
            throw new ModelContractException(
                    "Model response taskType does not match the request taskType");
        }
    }
}