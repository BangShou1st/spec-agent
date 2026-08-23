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
     */
    @Transactional
    public void acceptProposal(UUID proposalId, String decidedBy) {
        repository.updateStatus(proposalId, ProposalStatus.ACCEPTED,
                Instant.now(), decidedBy);
    }

    /**
     * Rejects a proposal, marking it as REJECTED with the current timestamp.
     */
    @Transactional
    public void rejectProposal(UUID proposalId, String decidedBy) {
        repository.updateStatus(proposalId, ProposalStatus.REJECTED,
                Instant.now(), decidedBy);
    }

    /**
     * Expires a proposal, marking it as EXPIRED with the current timestamp.
     */
    @Transactional
    public void expireProposal(UUID proposalId) {
        repository.updateStatus(proposalId, ProposalStatus.EXPIRED,
                Instant.now(), "system");
    }

    public Optional<AgentProposal> getProposal(UUID id) {
        return repository.findById(id);
    }

    public List<AgentProposal> getPendingProposals(UUID projectId) {
        return repository.findByProjectAndStatus(projectId, ProposalStatus.PROPOSED);
    }

    public List<AgentProposal> getByStatus(UUID projectId, ProposalStatus status) {
        return repository.findByProjectAndStatus(projectId, status);
    }
}
