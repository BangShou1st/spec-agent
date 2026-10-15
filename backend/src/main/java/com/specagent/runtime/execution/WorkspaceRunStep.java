package com.specagent.runtime.execution;

/**
 * 文件名:WorkspaceRunStep.java
 *
 * 用途:画布步骤账本的一行(design §5.5)。
 *
 * @param runId       agent_runs.id
 * @param stepKey     稳定步骤标识(不含 attempt)
 * @param inputHash   步骤输入的 SHA-256(重试输入不一致时拒绝复用)
 * @param state       步骤状态
 * @param originEpoch 写入该状态的执行代次
 * @param resultRef   可验证的结果引用(如 answer:{id} / patch:{id} / node:{id})
 * @param retryClass  重试类别
 */
public record WorkspaceRunStep(java.util.UUID runId,
                               String stepKey,
                               String inputHash,
                               WorkspaceRunStepState state,
                               long originEpoch,
                               String resultRef,
                               WorkspaceRunStepRetryClass retryClass,
                               java.time.Instant updatedAt) {

    public boolean succeeded() {
        return state == WorkspaceRunStepState.SUCCEEDED;
    }

    /** 结果不确定:恢复时必须停止而不是重放(design §7)。 */
    public boolean outcomeUnknown() {
        return state == WorkspaceRunStepState.UNKNOWN;
    }
}
