package com.specagent.agent;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Records controlled agent executions.
 *
 * <p>An agent run does not own persistent requirement state; the runtime kernel
 * remains the source of truth. This service only persists run metadata and the
 * ids of records produced by the run.
 */
@Service
public class AgentRunService {

    private final AgentRunRepository agentRunRepository;

    public AgentRunService(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    public AgentRun create(UUID projectId,
                           UUID routeId,
                           AgentRunTriggerType triggerType,
                           UUID inputNodeId,
                           UUID createdByRunId) {
        UUID runId = Ids.random();
        Instant now = Instant.now();
        AgentRun run = new AgentRun(runId, projectId, routeId, triggerType, inputNodeId, null,
                null, null, null, null, AgentRunStatus.CREATED, null, now, null);
        agentRunRepository.save(run);
        return run;
    }

    public void complete(UUID runId, AgentRunStatus status, String trace) {
        agentRunRepository.updateStatus(runId, status, Instant.now(), trace);
    }

    /**
     * Records that the run's context snapshot was built and frozen.
     */
    public void attachContext(UUID runId, UUID contextSnapshotId, String trace) {
        agentRunRepository.attachContext(runId, contextSnapshotId, trace);
    }

    /**
     * Records that the model adapter was called for this run.
     */
    public void markModelCalled(UUID runId, String trace) {
        agentRunRepository.markModelCalled(runId, trace);
    }

    /**
     * Records that reflection gates ran over the model's proposal.
     */
    public void markReflected(UUID runId, String trace) {
        agentRunRepository.markReflected(runId, trace);
    }

    /**
     * Records the node persisted by this run.
     */
    public void markPersistedNode(UUID runId, UUID producedNodeId, String trace) {
        agentRunRepository.markPersistedNode(runId, producedNodeId, trace);
    }

    /**
     * Marks a run failed. Failure is terminal.
     */
    public void fail(UUID runId, String trace) {
        agentRunRepository.fail(runId, trace);
    }

    public Optional<AgentRun> getRun(UUID runId) {
        return agentRunRepository.findById(runId);
    }

    public java.util.List<AgentRun> listByProject(UUID projectId) {
        return agentRunRepository.findByProject(projectId);
    }
}
