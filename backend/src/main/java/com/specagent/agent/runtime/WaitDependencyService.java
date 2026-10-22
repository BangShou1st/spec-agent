package com.specagent.agent.runtime;

import com.specagent.agent.action.ActionEligibilityDependencyPort;
import com.specagent.agent.protocol.*;
import com.specagent.capability.*;
import com.specagent.workspace.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Authorization, freeze/replay and WAIT registration; never starts an external job. */
@Service
public class WaitDependencyService implements ActionEligibilityDependencyPort {
    private final WaitDependencyRepository waits;
    private final CapabilityInvocationRepository invocations;
    private final CapabilityRegistry registry;
    private final ProjectService projects;
    public WaitDependencyService(WaitDependencyRepository waits, CapabilityInvocationRepository invocations,
            CapabilityRegistry registry, ProjectService projects) {
        this.waits = waits; this.invocations = invocations; this.registry = registry; this.projects = projects;
    }

    @Override @Transactional
    public PendingDependencyProjection freeze(AgentRequestEnvelope request) {
        projects.authorizeWrite(request.snapshot().projectId());
        waits.requireScope(request);
        var existing = waits.frozen(request.runId());
        if (existing.isPresent()) return authorized(request, existing.get());
        var s = request.snapshot();
        var visible = s.availableCapabilities().stream().map(com.specagent.agent.protocol.CapabilityDescriptor::id).toList();
        var dependencies = new ArrayList<PendingDependencyProjection.Dependency>();
        for (var dependency : waits.candidates(request)) {
            if (!visible.contains(dependency.capabilityId())) continue;
            var adapter = registry.findAdapter(dependency.capabilityId()).orElse(null);
            if (!(adapter instanceof DeferredCapabilityAdapter)) continue;
            dependencies.add(dependency);
        }
        // The exact settled observation is included even if the recent-result window has moved.
        var completed = waits.resumedInvocation(request.runId()).map(id -> List.of(result(record(id))))
                .orElse(List.of());
        var projection = new PendingDependencyProjection(PendingDependencyProjection.VERSION,
                request.runId(), UUID.fromString(s.snapshotId()), s.contextHash(), s.projectId(),
                s.routeId(), s.anchorNodeId(), dependencies, completed);
        authorized(request, projection);
        if (waits.freeze(projection)) return projection;
        return authorized(request, waits.frozen(request.runId())
                .orElseThrow(() -> new IllegalStateException("WAIT_FREEZE_WINNER_MISSING")));
    }

    private PendingDependencyProjection authorized(AgentRequestEnvelope request, PendingDependencyProjection projection) {
        projection.requireScope(request.runId(), request.snapshot());
        for (var dependency : projection.dependencies()) {
            if (request.snapshot().availableCapabilities().stream().noneMatch(c -> c.id().equals(dependency.capabilityId())))
                throw new IllegalStateException("WAIT_CAPABILITY_NOT_VISIBLE");
            waits.requireOwner(dependency.invocationId(), projection, false);
            var record = record(dependency.invocationId());
            if (!dependency.ownerRunId().equals(record.runId()) || !dependency.capabilityId().equals(record.capabilityId()))
                throw new IllegalStateException("WAIT_DEPENDENCY_IDENTITY_MISMATCH");
            authorize(projection, record);
        }
        for (var completed : projection.completedResults()) {
            var record = record(UUID.fromString(completed.invocationId()));
            waits.requireOwner(record.id(), projection, false);
            if (!completed.capabilityId().equals(record.capabilityId()))
                throw new IllegalStateException("WAIT_RESULT_IDENTITY_MISMATCH");
            authorize(projection, record);
        }
        return projection;
    }

    private void authorize(PendingDependencyProjection projection, CapabilityInvocationRecord record) {
        if (!projection.projectId().equals(record.projectId()) || record.runId() == null)
            throw new IllegalStateException("WAIT_DEPENDENCY_SCOPE_INVALID");
        var adapter = registry.findAdapter(record.capabilityId()).orElse(null);
        if (!(adapter instanceof DeferredCapabilityAdapter deferred))
            throw new IllegalStateException("WAIT_ADAPTER_UNAVAILABLE");
        registry.requireAuthorizedRecordedResult(record);
        deferred.requireAuthorizedCompletion(record);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void subscribe(AgentRequestEnvelope request, ActionProposal proposal) {
        projects.authorizeWrite(request.snapshot().projectId());
        waits.requireScope(request);
        var projection = waits.frozen(request.runId())
                .orElseThrow(() -> new IllegalStateException("WAIT_PROJECTION_MISSING"));
        if (!AgentProtocol.INPUT_PROTOCOL_VERSION_V4.equals(request.protocolVersion())
                || !"WAIT".equals(proposal.actionFamily()) || request.pendingDependencies() == null
                || !AgentContracts.write(projection).equals(AgentContracts.write(request.pendingDependencies()))
                || !proposal.baseContextSnapshotId().equals(projection.snapshotId())
                || !proposal.baseContextHash().equals(projection.contextHash()))
            throw new IllegalStateException("WAIT_PROJECTION_MISMATCH");
        projection.requireScope(request.runId(), request.snapshot());
        if (proposal.payload().size() != 1 || !(proposal.payload().get("dependencyRef") instanceof String ref))
            throw new IllegalArgumentException("WAIT_REQUIRES_DEPENDENCY_REF");
        UUID id;
        try { id = UUID.fromString(ref); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("WAIT_REQUIRES_DEPENDENCY_REF"); }
        var dependency = projection.dependencies().stream().filter(d -> d.invocationId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("WAIT_DEPENDENCY_NOT_PROJECTED"));
        // Adapter/source locks precede invocation locks, as in the existing completion path.
        var record = record(id);
        if (!dependency.ownerRunId().equals(record.runId()) || !dependency.capabilityId().equals(record.capabilityId()))
            throw new IllegalStateException("WAIT_DEPENDENCY_IDENTITY_MISMATCH");
        authorize(projection, record);
        waits.requireOwner(id, projection, true);
        waits.subscribe(projection, id, proposal.proposalId());
    }

    private CapabilityInvocationRecord record(UUID id) {
        return invocations.findById(id).orElseThrow(() -> new IllegalStateException("WAIT_DEPENDENCY_MISSING"));
    }

    @SuppressWarnings("unchecked")
    private CapabilityResultView result(CapabilityInvocationRecord record) {
        var stored = record.result() == null ? Map.<String, Object>of() : record.result();
        return new CapabilityResultView(record.id().toString(), record.capabilityId(), record.status().name(),
                stored.get("content") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(),
                stored.get("sourceRefs") instanceof List<?> refs ? refs.stream().map(String::valueOf).toList() : List.of(),
                stored.get("provenance") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
    }
}
