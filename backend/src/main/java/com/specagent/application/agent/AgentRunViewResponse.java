package com.specagent.application.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.loop.AgentRunChainReadService;
import com.specagent.agent.runevent.RunProgressView;

import java.util.UUID;

/**
 * Typed view of one agent run.
 *
 * <p>Replaces the hand-assembled {@code LinkedHashMap} that used to be built in
 * the command service. <b>The JSON field names are a frozen wire contract</b>:
 * {@code frontend/src/api/agentRuns.ts} declares {@code AgentRunView} with
 * exactly these names, so component names and their declaration order must not
 * change. Nullable fields stay nullable (Jackson emits {@code null}, matching
 * the previous map behaviour).
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

    /** Assembly lives with the view model rather than in the orchestration service. */
    public static AgentRunViewResponse from(AgentRun run,
                                            String phase,
                                            AgentRunChainReadService.AgentRunChainRead chain,
                                            RunProgressView progress) {
        return new AgentRunViewResponse(
                run.id().toString(),
                run.projectId().toString(),
                run.routeId().toString(),
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
