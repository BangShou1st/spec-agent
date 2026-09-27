package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunChainReadService;
import com.specagent.agent.runevent.RunProgressView;

import java.util.UUID;

/**
 * 文件名:AgentRunViewResponse.java
 *
 * 用途:单个 AgentRun 的类型化视图(读模型),供 API 层返回 run 详情。
 *
 * 取代原先在命令服务里手工拼装的 {@code LinkedHashMap}。
 * 【JSON 字段名是冻结的线上契约】:{@code frontend/src/api/agentRuns.ts}
 * 中的 {@code AgentRunView} 声明了完全相同的字段名,因此 record 组件名与声明
 * 顺序不可更改。可为空的字段保持可空(Jackson 输出 {@code null},与原先
 * Map 的行为一致)。
 */
public record AgentRunViewResponse(
        String runId,
        String projectId,
        String routeId,
        String operation,
        String status,
        String phase,
        String producedNodeId,
        String producedAnswerId,
        String producedPatchId,
        String producedSpecSnapshotId,
        String childRunId,
        boolean continuationPending,
        String respondMessage,
        RunProgressView progress) {

    /** 视图的组装逻辑跟随视图模型本身,而不是放在编排服务里。 */
    public static AgentRunViewResponse from(AgentRun run,
                                            String phase,
                                            AgentRunChainReadService.AgentRunChainRead chain,
                                            RunProgressView progress) {
        // routeId 可为空:游离节点(floating node)的 NODE_QUERY run 明确
        // 支持无 route 入队,视图契约必须输出 null 而不是抛 NPE。
        return new AgentRunViewResponse(
                run.id().toString(),
                run.projectId().toString(),
                toStringOrNull(run.routeId()),
                run.operation() != null ? run.operation() : "",
                run.status().code(),
                phase,
                toStringOrNull(run.producedNodeId()),
                toStringOrNull(run.producedAnswerId()),
                toStringOrNull(run.producedPatchId()),
                toStringOrNull(run.producedSpecSnapshotId()),
                chain.childRunId() == null ? null : chain.childRunId().toString(),
                chain.continuationPending(),
                chain.respondMessage(),
                progress);
    }

    private static String toStringOrNull(UUID value) {
        return value == null ? null : value.toString();
    }
}
