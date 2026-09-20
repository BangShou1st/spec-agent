package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalAcceptanceService;
import com.specagent.agent.policy.ProposalStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Advisor proposal lifecycle API. Listing filters by the requested status and,
 * optionally, by the trigger type of the run that produced each proposal;
 * accepting a proposal re-validates it against current graph facts and
 * executes it through the runtime command layer in one transaction.
 */
@RestController
@RequestMapping("/api/v1")
public class AgentProposalController {

    private final AgentProposalService proposalService;
    private final ProposalAcceptanceService acceptanceService;
    private final AgentRunService agentRunService;

    public AgentProposalController(AgentProposalService proposalService,
                                   ProposalAcceptanceService acceptanceService,
                                   AgentRunService agentRunService) {
        this.proposalService = proposalService;
        this.acceptanceService = acceptanceService;
        this.agentRunService = agentRunService;
    }

    /**
     * Lists proposals, optionally narrowed by the producing run's trigger type.
     *
     * <p>Both filter parameters are optional and comma-separated
     * ({@code AgentRunTriggerType} codes, e.g. {@code node_query}); omitting
     * them returns the unfiltered list exactly as before.
     * <ul>
     *   <li>{@code triggerType} — keep only proposals whose run has one of
     *       these trigger types.</li>
     *   <li>{@code excludeTriggerType} — drop proposals whose run has one of
     *       these trigger types.</li>
     * </ul>
     *
     * <p>A proposal whose run row is gone has an unknown ({@code null})
     * trigger type: it is dropped by an inclusion filter and kept by an
     * exclusion filter, which is what the previous client-side filtering did.
     */
    @GetMapping("/projects/{projectId}/proposals")
    public ResponseEntity<List<Map<String, Object>>> listProposals(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "PROPOSED") String status,
            @RequestParam(name = "triggerType", required = false) String triggerType,
            @RequestParam(name = "excludeTriggerType", required = false) String excludeTriggerType) {
        ProposalStatus proposalStatus = ProposalStatus.fromCode(status);
        Set<String> include = triggerTypeCodes(triggerType);
        Set<String> exclude = triggerTypeCodes(excludeTriggerType);

        // One project-scoped run read for the whole page instead of two
        // getRun() round-trips per proposal (the previous N+1).
        Map<UUID, AgentRun> runsById = agentRunService.listByProject(projectId).stream()
                .collect(Collectors.toMap(AgentRun::id, run -> run, (first, second) -> first));

        List<Map<String, Object>> summaries = new ArrayList<>();
        for (AgentProposal proposal : proposalService.getByStatus(projectId, proposalStatus)) {
            String resolvedTriggerType = resolveTriggerType(proposal.runId(), runsById);
            if (!matchesTriggerFilter(resolvedTriggerType, include, exclude)) {
                continue;
            }
            summaries.add(toSummary(proposal, resolvedTriggerType,
                    resolveInputNodeId(proposal.runId(), runsById)));
        }
        return ResponseEntity.ok(summaries);
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
        body.put("originRunId", result.originRunId() == null
                ? null : result.originRunId().toString());
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

    private Map<String, Object> toSummary(AgentProposal proposal,
                                          String triggerType,
                                          String inputNodeId) {
        // LinkedHashMap (not Map.of) so null decidedAt/decidedBy for PROPOSED
        // proposals do not throw NullPointerException.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("proposalId", proposal.id().toString());
        summary.put("runId", proposal.runId() == null ? null : proposal.runId().toString());
        summary.put("triggerType", triggerType);
        summary.put("inputNodeId", inputNodeId);
        summary.put("routeId", proposal.routeId() == null ? null : proposal.routeId().toString());
        summary.put("actionFamily", proposal.actionFamily());
        summary.put("status", proposal.status().code());
        summary.put("createdAt", proposal.createdAt().toString());
        summary.put("decidedAt", proposal.decidedAt() != null ? proposal.decidedAt().toString() : null);
        summary.put("decidedBy", proposal.decidedBy() != null ? proposal.decidedBy() : null);
        return summary;
    }

    /** Parses a comma-separated trigger-type code list; blank means "no filter". */
    private static Set<String> triggerTypeCodes(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean matchesTriggerFilter(String triggerType,
                                                Set<String> include,
                                                Set<String> exclude) {
        if (triggerType != null && exclude.contains(triggerType)) {
            return false;
        }
        if (!include.isEmpty()) {
            return triggerType != null && include.contains(triggerType);
        }
        return true;
    }

    /**
     * The run trigger type that produced this proposal, derived from the
     * proposal's AgentRun. The frontend must NOT infer the query origin from
     * {@code inputNodeId} — every run type carries one — so the trigger type is
     * the explicit filter that keeps NodeQuery recovery to node_query proposals
     * only. Null when the run is gone.
     */
    private static String resolveTriggerType(UUID runId, Map<UUID, AgentRun> runsById) {
        if (runId == null) {
            return null;
        }
        AgentRun run = runsById.get(runId);
        return run == null ? null : run.triggerType().code();
    }

    /**
     * The canonical anchor node of the NodeQuery run that produced this
     * proposal, so the frontend can reconnect a pending proposal to its
     * Inspector anchor even after a page reload. Null when the run is gone.
     */
    private static String resolveInputNodeId(UUID runId, Map<UUID, AgentRun> runsById) {
        if (runId == null) {
            return null;
        }
        AgentRun run = runsById.get(runId);
        if (run == null || run.inputNodeId() == null) {
            return null;
        }
        return run.inputNodeId().toString();
    }
}
