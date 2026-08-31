package com.specagent.api.agent;

import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalAcceptanceService;
import com.specagent.agent.policy.ProposalStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Advisor proposal lifecycle API. Listing filters by the requested status;
 * accepting a proposal re-validates it against current graph facts and
 * executes it through the runtime command layer in one transaction.
 */
@RestController
@RequestMapping("/api/v1")
public class AgentProposalController {

    private final AgentProposalService proposalService;
    private final ProposalAcceptanceService acceptanceService;

    public AgentProposalController(AgentProposalService proposalService,
                                   ProposalAcceptanceService acceptanceService) {
        this.proposalService = proposalService;
        this.acceptanceService = acceptanceService;
    }

    @GetMapping("/projects/{projectId}/proposals")
    public ResponseEntity<List<Map<String, Object>>> listProposals(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "PROPOSED") String status) {
        ProposalStatus proposalStatus = ProposalStatus.fromCode(status);
        List<AgentProposal> proposals = proposalService.getByStatus(projectId, proposalStatus);
        return ResponseEntity.ok(proposals.stream().map(this::toSummary).toList());
    }

    @PostMapping("/proposals/{proposalId}/accept")
    public ResponseEntity<Map<String, Object>> acceptProposal(
            @PathVariable UUID proposalId) {
        ProposalAcceptanceService.AcceptedProposalResult result =
                acceptanceService.acceptAndExecute(proposalId, "user");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", proposalId.toString());
        body.put("status", "ACCEPTED");
        body.put("actionFamily", result.actionFamily());
        body.put("producedNodeId", result.producedNodeId() == null
                ? null : result.producedNodeId().toString());
        body.put("relationId", result.relationId() == null
                ? null : result.relationId().toString());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public ResponseEntity<Map<String, Object>> rejectProposal(
            @PathVariable UUID proposalId) {
        proposalService.rejectProposal(proposalId, "user");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", proposalId.toString());
        body.put("status", "REJECTED");
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toSummary(AgentProposal proposal) {
        // LinkedHashMap (not Map.of) so null decidedAt/decidedBy for PROPOSED
        // proposals do not throw NullPointerException.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("proposalId", proposal.id().toString());
        summary.put("actionFamily", proposal.actionFamily());
        summary.put("status", proposal.status().code());
        summary.put("createdAt", proposal.createdAt().toString());
        summary.put("decidedAt", proposal.decidedAt() != null ? proposal.decidedAt().toString() : null);
        summary.put("decidedBy", proposal.decidedBy() != null ? proposal.decidedBy() : null);
        return summary;
    }
}
