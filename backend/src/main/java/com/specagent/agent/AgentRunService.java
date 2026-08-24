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
     * Legacy non-idempotent run creation entry point. Client-idempotent
     * mutation paths must call {@link #createWithIdempotency} with their
     * canonical request fingerprint.
     */
    public AgentRun create(UUID projectId,
                           UUID routeId,
                           AgentRunTriggerType triggerType,
                           UUID inputNodeId,
                           UUID createdByRunId,
                           String operation,
                           String idempotencyKey) {
        return createWithIdempotency(projectId, routeId, triggerType, inputNodeId,
                createdByRunId, operation, idempotencyKey, null).run();
    }

    /**
     * Resolves an already-persisted idempotent request before callers inspect
     * mutable graph state. A matching client fingerprint replays the existing
     * run; a mismatched request using the same project/key is rejected.
     */
    public Optional<AgentRun> findIdempotentReplay(UUID projectId,
                                                    String idempotencyKey,
                                                    String requestFingerprint) {
        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedKey == null) {
            return Optional.empty();
        }
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "Client-idempotent agent runs require a request fingerprint");
        }
        Optional<AgentRun> existing = agentRunRepository
                .findByProjectIdAndIdempotencyKey(projectId, normalizedKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (requestFingerprint.equals(existing.get().requestFingerprint())) {
            return existing;
        }
        throw new IdempotencyKeyReusedException();
    }

    /**
     * Creates a run and returns whether this caller inserted it. For a
     * client-idempotent request the database arbitrates the project/key race;
     * a matching fingerprint returns the persisted winner and a mismatch is a
     * deterministic key-reuse conflict.
     */
    public CreateResult createWithIdempotency(UUID projectId,
                                              UUID routeId,
                                              AgentRunTriggerType triggerType,
                                              UUID inputNodeId,
                                              UUID createdByRunId,
                                              String operation,
                                              String idempotencyKey,
                                              String requestFingerprint) {
        UUID runId = Ids.random();
        Instant now = Instant.now();
        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedKey != null && (requestFingerprint == null || requestFingerprint.isBlank())) {
            throw new IllegalArgumentException(
                    "Client-idempotent agent runs require a request fingerprint");
        }
        AgentRun run = new AgentRun(runId, projectId, routeId, triggerType, inputNodeId, null,
                null, null, null, null, AgentRunStatus.CREATED, null, operation,
                normalizedKey, normalizedKey == null ? null : requestFingerprint, now, null);
        if (normalizedKey == null) {
            agentRunRepository.save(run);
            return new CreateResult(run, true);
        }

        if (agentRunRepository.insertIfAbsent(run)) {
            return new CreateResult(run, true);
        }

        AgentRun existing = agentRunRepository
                .findByProjectIdAndIdempotencyKey(projectId, normalizedKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotent agent-run row missing after insert race"));
        if (requestFingerprint.equals(existing.requestFingerprint())) {
            return new CreateResult(existing, false);
        }
        throw new IdempotencyKeyReusedException();
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

    public void attachContext(UUID runId, UUID contextSnapshotId, String trace) {
        agentRunRepository.attachContext(runId, contextSnapshotId, trace);
    }

    public void markModelCalled(UUID runId, String trace) {
        agentRunRepository.markModelCalled(runId, trace);
    }

    public void markReflected(UUID runId, String trace) {
        agentRunRepository.markReflected(runId, trace);
    }

    public void markPersistedNode(UUID runId, UUID producedNodeId, String trace) {
        agentRunRepository.markPersistedNode(runId, producedNodeId, trace);
    }

    public void markPersistedAnswer(UUID runId, UUID producedAnswerId, String trace) {
        agentRunRepository.markPersistedAnswer(runId, producedAnswerId, trace);
    }

    public void markPersistedAnswerPatch(UUID runId, UUID producedPatchId, String trace) {
        agentRunRepository.markPersistedAnswerPatch(runId, producedPatchId, trace);
    }

    public void markPersistedSpecSnapshot(UUID runId, UUID producedSpecSnapshotId, String trace) {
        agentRunRepository.markPersistedSpecSnapshot(runId, producedSpecSnapshotId, trace);
    }

    public void fail(UUID runId, String trace) {
        agentRunRepository.fail(runId, trace);
    }

    public Optional<AgentRun> getRun(UUID runId) {
        return agentRunRepository.findById(runId);
    }

    public java.util.List<AgentRun> listByProject(UUID projectId) {
        return agentRunRepository.findByProject(projectId);
    }

    public record CreateResult(AgentRun run, boolean inserted) {
    }
}
