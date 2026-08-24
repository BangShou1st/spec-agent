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
        return create(projectId, routeId, triggerType, inputNodeId, createdByRunId, null);
    }

    public AgentRun create(UUID projectId,
                           UUID routeId,
                           AgentRunTriggerType triggerType,
                           UUID inputNodeId,
                           UUID createdByRunId,
                           String operation) {
        return create(projectId, routeId, triggerType, inputNodeId, createdByRunId, operation, null);
    }

    /**
     * Creates an agent run with an optional client idempotency key. When the
     * key is non-blank the insert is atomic and idempotent: if another
     * request already persisted a run with this key — including a concurrent
     * one that won the insert race — that existing run is returned unchanged
     * and only one row ever persists.
     */
    public AgentRun create(UUID projectId,
                           UUID routeId,
                           AgentRunTriggerType triggerType,
                           UUID inputNodeId,
                           UUID createdByRunId,
                           String operation,
                           String idempotencyKey) {
        UUID runId = Ids.random();
        Instant now = Instant.now();
        AgentRun run = new AgentRun(runId, projectId, routeId, triggerType, inputNodeId, null,
                null, null, null, null, AgentRunStatus.CREATED, null, operation,
                normalizeKey(idempotencyKey), now, null);
        if (run.idempotencyKey() != null) {
            // Insert-if-absent is atomic; whether this caller won or lost the
            // race, re-read and return the persisted winner so every replayed
            // key resolves to exactly ONE run.
            agentRunRepository.insertIfAbsent(run);
            return agentRunRepository.findByIdempotencyKey(run.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotent agent-run row missing after insert: "
                                    + run.idempotencyKey()));
        }
        agentRunRepository.save(run);
        return run;
    }

    private String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
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
     * Records the answer persisted by this run.
     */
    public void markPersistedAnswer(UUID runId, UUID producedAnswerId, String trace) {
        agentRunRepository.markPersistedAnswer(runId, producedAnswerId, trace);
    }

    /**
     * Records the answer patch persisted by this run.
     */
    public void markPersistedAnswerPatch(UUID runId, UUID producedPatchId, String trace) {
        agentRunRepository.markPersistedAnswerPatch(runId, producedPatchId, trace);
    }

    /**
     * Records the spec snapshot persisted by this run.
     */
    public void markPersistedSpecSnapshot(UUID runId, UUID producedSpecSnapshotId, String trace) {
        agentRunRepository.markPersistedSpecSnapshot(runId, producedSpecSnapshotId, trace);
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
