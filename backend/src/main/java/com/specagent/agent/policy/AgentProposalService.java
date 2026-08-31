package com.specagent.agent.policy;

import com.specagent.agent.contract.ActionProposal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of action proposals: creation, acceptance,
 * rejection, and expiration. Every state transition is traceable
 * through decidedAt/decidedBy timestamps.
 */
@Service
public class AgentProposalService {

    private final AgentProposalRepository repository;

    public AgentProposalService(AgentProposalRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a new proposal in PROPOSED status. Idempotent by the
     * database-backed unique index: when a proposal with the same key already
     * exists — including one inserted concurrently by another worker — it is
     * returned unchanged and only one row ever persists.
     */
    @Transactional
    public AgentProposal createProposal(ActionProposal actionProposal,
                                        UUID runId, UUID projectId, UUID routeId) {
        AgentProposal proposal = new AgentProposal(
                actionProposal.proposalId(),
                runId, projectId, routeId,
                actionProposal.actionFamily(),
                actionProposal.payload(),
                actionProposal.anchorRefs(),
                ProposalStatus.PROPOSED,
                actionProposal.baseContextSnapshotId(),
                actionProposal.baseContextHash(),
                actionProposal.idempotencyKey(),
                Instant.now(), null, null);
        if (!repository.insertIfAbsent(proposal)) {
            // Lost the insert race: the persisted winner is the shared truth.
            return repository.findByIdempotencyKey(actionProposal.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotent proposal row missing after losing its insert race: "
                                    + actionProposal.idempotencyKey()));
        }
        return proposal;
    }

    /**
     * Accepts a proposal, marking it as ACCEPTED with the current timestamp.
     * The transition out of PROPOSED is atomic and single-winner: a proposal
     * already decided (ACCEPTED/REJECTED/EXPIRED by another request racing
     * this one) raises {@link ProposalAlreadyDecidedException} instead of
     * overwriting the terminal state.
     */
    @Transactional
    public void acceptProposal(UUID proposalId, String decidedBy) {
        requirePending(proposalId);
        if (!repository.transitionFromProposed(proposalId, ProposalStatus.ACCEPTED,
                Instant.now(), decidedBy)) {
            throw alreadyDecided(proposalId);
        }
    }

    /**
     * Rejects a proposal, marking it as REJECTED with the current timestamp.
     * Same single-winner rule as acceptance: an existing terminal state is
     * never overwritten.
     */
    @Transactional
    public void rejectProposal(UUID proposalId, String decidedBy) {
        requirePending(proposalId);
        if (!repository.transitionFromProposed(proposalId, ProposalStatus.REJECTED,
                Instant.now(), decidedBy)) {
            throw alreadyDecided(proposalId);
        }
    }

    /**
     * Expires a proposal, marking it as EXPIRED with the current timestamp.
     * Same single-winner rule as acceptance: an existing terminal state is
     * never overwritten.
     */
    @Transactional
    public void expireProposal(UUID proposalId) {
        requirePending(proposalId);
        if (!repository.transitionFromProposed(proposalId, ProposalStatus.EXPIRED,
                Instant.now(), "system")) {
            throw alreadyDecided(proposalId);
        }
    }

    /**
     * Locks the proposal row and fails fast when it is already decided or
     * does not exist. The lock is held to the end of the transaction, so a
     * racing decision on the same proposal waits here and re-reads the
     * committed status before its own CAS attempt.
     */
    private void requirePending(UUID proposalId) {
        AgentProposal locked = repository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found: " + proposalId));
        if (locked.status() != ProposalStatus.PROPOSED) {
            throw new ProposalAlreadyDecidedException(locked.status().code());
        }
    }

    /**
     * Builds the loser error after the CAS observed zero affected rows. The
     * status is read fresh (the winner's row is already committed at that
     * point under READ_COMMITTED), so the reported state matches the DB.
     */
    private ProposalAlreadyDecidedException alreadyDecided(UUID proposalId) {
        String currentStatus = repository.findById(proposalId)
                .map(p -> p.status().code())
                .orElse("UNKNOWN");
        return new ProposalAlreadyDecidedException(currentStatus);
    }

    /**
     * Locking read for lifecycle decisions. Callers that are about to execute
     * work on behalf of a PROPOSED proposal (acceptance) must take the row
     * lock up front so the lock spans their entire transaction.
     */
    public Optional<AgentProposal> getProposalForUpdate(UUID id) {
        return repository.findByIdForUpdate(id);
    }

    public Optional<AgentProposal> getProposal(UUID id) {
        return repository.findById(id);
    }

    /**
     * Finds the proposal created by a specific agent run, if any. Node-query
     * runs that downgrade a mutation action to an approval produce exactly one
     * proposal linked by {@code runId}; read-only runs never create one.
     */
    public Optional<AgentProposal> findByRunId(UUID runId) {
        return repository.findByRunId(runId);
    }

    public List<AgentProposal> getPendingProposals(UUID projectId) {
        return repository.findByProjectAndStatus(projectId, ProposalStatus.PROPOSED);
    }

    public List<AgentProposal> getByStatus(UUID projectId, ProposalStatus status) {
        return repository.findByProjectAndStatus(projectId, status);
    }
}
