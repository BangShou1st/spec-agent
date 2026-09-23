package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;

/**
 * 202 ACCEPTED body of {@code POST /projects/{projectId}/agent-runs}.
 *
 * <p>Replaces the hand-assembled map. Field names/order are the frozen wire
 * contract for the enqueue response ({@code runId}, {@code operation},
 * {@code status}, {@code phase}).
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
