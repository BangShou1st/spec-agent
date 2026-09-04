package com.specagent.eval;

import com.specagent.agent.AgentRun;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.context.ContextSnapshot;
import com.specagent.trace.SemanticTrace;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical state observed around one attempt. The harness only observes
 * and orchestrates — every fact here is read from the Java runtime, never
 * re-derived by a second model of the graph.
 */
public record AttemptContext(
        UUID projectId,
        UUID runId,
        AgentRun run,
        List<AgentRunEventView> events,
        StateSummary preState,
        StateSummary postState,
        Map<String, Integer> stateDelta,
        String actualPrimaryAction,
        String executionResult,
        List<AgentProposal> proposals,
        Map<String, Integer> capabilityInvocations,
        List<UUID> preAnswerIds,
        List<UUID> postAnswerIds,
        ContextSnapshot decisionSnapshot,
        SemanticTrace semanticTrace,
        List<String> observedStages,
        int providerRetries,
        long latencyMs,
        Map<String, Long> stageLatencyMs,
        String failureDetail) {

    /** Minimal view over a run event (type + payload only). */
    public record AgentRunEventView(String eventType, Map<String, Object> payload) {
    }
}
