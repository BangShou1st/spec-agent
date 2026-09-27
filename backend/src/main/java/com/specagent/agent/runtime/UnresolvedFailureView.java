package com.specagent.agent.runtime;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:UnresolvedFailureView.java
 *
 * 用途:项目内"未解决失败"的读取视图(任务级恢复契约的读侧)。每个条目
 * 绑定完整的任务身份:failedRunId、operation、routeId(允许 null——无路线
 * 任务)、sourceNodeId(发起节点),以及服务端判定的恢复动作类别。客户端
 * 绝不自行决定"重试什么";按钮文案与允许动作都来自这里的
 * {@code availableAction}。
 *
 * JSON 字段名是线上契约(前端 {@code UnresolvedFailure} 类型一一对应)。
 */
public record UnresolvedFailureView(
        String runId,
        String projectId,
        String operation,
        String routeId,
        String sourceNodeId,
        String reasonCode,
        String reasonSummary,
        /** 服务端允许的恢复动作:RETRY_GENERATION / CONTINUE_PROCESSING /
         *  RETRY_REGENERATE / RETRY_SPEC / RETRY_NODE_QUERY /
         *  GO_TO_MODEL_SETTINGS / STALE。 */
        String availableAction,
        String actionLabel,
        boolean stale,
        /** 该失败已有一个在途的重试/接续 run 时非空:UI 禁用重试并显示进度。 */
        String retryRunId,
        String retryStatus,
        Instant createdAt) {

    public static UnresolvedFailureView of(UUID projectId, AgentRun run, String reasonCode,
                                           String reasonSummary, String availableAction,
                                           String actionLabel, boolean stale,
                                           AgentRun retryRun) {
        return new UnresolvedFailureView(
                run.id().toString(),
                projectId.toString(),
                run.operation() == null ? "" : run.operation(),
                run.routeId() == null ? null : run.routeId().toString(),
                run.inputNodeId() == null ? null : run.inputNodeId().toString(),
                reasonCode,
                reasonSummary,
                availableAction,
                actionLabel,
                stale,
                retryRun == null ? null : retryRun.id().toString(),
                retryRun == null ? null : retryRun.status().code(),
                run.createdAt());
    }
}
