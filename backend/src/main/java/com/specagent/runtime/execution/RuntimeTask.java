package com.specagent.runtime.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:RuntimeTask.java
 *
 * 用途:runtime_tasks 行的不可变读视图(docs/v2/
 * PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §5.1)。
 */
public record RuntimeTask(UUID id,
                          RuntimeTaskFamily family,
                          UUID workspaceRunId,
                          UUID gaRunId,
                          String taskType,
                          RuntimeTaskState state,
                          UUID workerId,
                          long executionEpoch,
                          UUID leaseId,
                          Instant leaseUntil,
                          Instant heartbeatAt,
                          int attemptCount,
                          int maxAttempts,
                          Instant availableAt,
                          Instant createdAt,
                          Instant deadlineAt,
                          Instant cancelRequestedAt,
                          String inputRef,
                          String inputVersion,
                          String lastErrorCode,
                          String recoveryReason,
                          int protocolVersion,
                          TaskActor actor) {

    /** 归属的业务 Run id(恰好一个非空)。 */
    public UUID runId() {
        return family == RuntimeTaskFamily.WORKSPACE ? workspaceRunId : gaRunId;
    }

    public boolean isCancellationRequested() {
        return cancelRequestedAt != null;
    }

    /** 当前行的所有权 token;未认领或已释放时为 null。 */
    public ExecutionToken token() {
        if (state != RuntimeTaskState.LEASED || leaseId == null || workerId == null
                || executionEpoch < 1) {
            return null;
        }
        return new ExecutionToken(id, executionEpoch, leaseId, workerId, family, runId());
    }
}
