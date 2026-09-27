package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;

/**
 * 文件名:AcceptedRunView.java
 *
 * 用途:{@code POST /projects/{projectId}/agent-runs} 接口返回 202 ACCEPTED 时的响应体。
 *
 * 取代原先手工拼装的 Map。字段名与顺序是入队响应的冻结线上契约
 * ({@code runId}、{@code operation}、{@code status}、{@code phase}),
 * 供调用方(REST 层)在命令入队后立即返回给客户端。
 */
public record AcceptedRunView(
        String runId,
        String operation,
        String status,
        String phase) {

    public static AcceptedRunView from(AgentRun run, String phase) {
        return new AcceptedRunView(
                run.id().toString(),
                run.operation() == null ? "" : run.operation(),
                run.status().code(),
                phase);
    }
}
