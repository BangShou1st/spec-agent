package com.specagent.agent;

import com.specagent.agent.loop.LoopLinkage;
import com.specagent.common.Ids;
import org.springframework.dao.DuplicateKeyException;
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
        return createWithIdempotency(projectId, routeId, triggerType, inputNodeId,
                createdByRunId, operation, idempotencyKey, requestFingerprint,
                LoopLinkage.none());
    }

    /**
     * Loop-linkage variant of {@link #createWithIdempotency(UUID, UUID,
     * AgentRunTriggerType, UUID, UUID, String, String, String)}.
     *
     * <p>The {@code createdByRunId} creation hint keeps its legacy meaning
     * (unpersisted) and stays independent from {@code linkage}: the former
     * records who asked, the latter records which terminal boundary spawned
     * this run. Callers must not merge the two concepts.
     */
    public CreateResult createWithIdempotency(UUID projectId,
                                              UUID routeId,
                                              AgentRunTriggerType triggerType,
                                              UUID inputNodeId,
                                              UUID createdByRunId,
                                              String operation,
                                              String idempotencyKey,
                                              String requestFingerprint,
                                              LoopLinkage linkage) {
        UUID runId = Ids.random();
        Instant now = Instant.now();
        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedKey != null && (requestFingerprint == null || requestFingerprint.isBlank())) {
            throw new IllegalArgumentException(
                    "Client-idempotent agent runs require a request fingerprint");
        }
        LoopLinkage effectiveLinkage = linkage == null ? LoopLinkage.none() : linkage;
        AgentRun run = new AgentRun(runId, projectId, routeId, triggerType, inputNodeId, null,
                null, null, null, null, AgentRunStatus.CREATED, null, operation,
                normalizedKey, normalizedKey == null ? null : requestFingerprint, now, null,
                effectiveLinkage.parentRunId(), effectiveLinkage.rootRunId(),
                effectiveLinkage.cycleIndex());
        if (normalizedKey == null) {
            agentRunRepository.save(run);
            return new CreateResult(run, true);
        }

        try {
            if (agentRunRepository.insertIfAbsent(run)) {
                return new CreateResult(run, true);
            }
        } catch (DuplicateKeyException raced) {
            // Loop-linked continuation children race on TWO unique backstops:
            // the project-scoped idempotency key (covered by the ON CONFLICT
            // clause) and the V23 single-child index on parent_run_id (not
            // covered — a sibling transaction may commit the same parent's
            // child first while this row is in flight). Only that loop-linked
            // case may recover below; ordinary idempotent creates rethrow so
            // no unrelated uniqueness failure is ever swallowed.
            if (effectiveLinkage.parentRunId() == null) {
                throw raced;
            }
        }

        AgentRun existing = agentRunRepository
                .findByProjectIdAndIdempotencyKey(projectId, normalizedKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotent agent-run row missing after insert race"));
        if (requestFingerprint.equals(existing.requestFingerprint())) {
            if (effectiveLinkage.parentRunId() != null) {
                verifyContinuationWinner(effectiveLinkage, normalizedKey,
                        requestFingerprint, existing);
            }
            return new CreateResult(existing, false);
        }
        throw new IdempotencyKeyReusedException();
    }

    /**
     * Fail-closed check for a loop-linked insert loser: the reloaded winner
     * must be the same parent's child created under the same deterministic
     * key and fingerprint. A same-parent row under a different key, or a
     * same-key row for another parent, never aliases as success.
     */
    private void verifyContinuationWinner(LoopLinkage linkage,
                                          String normalizedKey,
                                          String requestFingerprint,
                                          AgentRun existing) {
        if (!linkage.parentRunId().equals(existing.parentRunId())
                || !normalizedKey.equals(existing.idempotencyKey())
                || !requestFingerprint.equals(existing.requestFingerprint())) {
            throw new IllegalStateException(
                    "Continuation child race resolved to a different chain row: "
                            + "expected parent " + linkage.parentRunId()
                            + " under key " + normalizedKey);
        }
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

    /** All runs that persisted the given Answer, in creation order. */
    public java.util.List<AgentRun> findByProducedAnswerId(UUID answerId) {
        return agentRunRepository.findByProducedAnswerId(answerId);
    }

    public record CreateResult(AgentRun run, boolean inserted) {
    }
}
