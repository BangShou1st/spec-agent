package com.specagent.agent.protocol;

import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** Immutable, run-owned V4 extension; live terminality is checked when subscribing. */
public record PendingDependencyProjection(String version, UUID runId, UUID snapshotId,
        String contextHash, UUID projectId, UUID routeId, UUID anchorNodeId,
        List<Dependency> dependencies, List<CapabilityResultView> completedResults) {
    public static final String VERSION = "pending-dependencies.v1";
    public PendingDependencyProjection {
        if (!VERSION.equals(version) || runId == null || snapshotId == null
                || contextHash == null || projectId == null || dependencies == null || completedResults == null
                || dependencies.size() > 20 || completedResults.size() > 1) {
            throw new AgentContractException("Invalid pending dependency projection");
        }
        dependencies = List.copyOf(dependencies);
        completedResults = List.copyOf(completedResults);
        var ids = new HashSet<UUID>();
        for (var dependency : dependencies) {
            if (!ids.add(dependency.invocationId()) || dependency.ownerRunId().equals(runId)
                    || routeId == null) throw new AgentContractException("Invalid dependency owner or duplicate");
        }
        for (var result : completedResults) {
            if (!("SUCCEEDED".equals(result.status()) || "FAILED".equals(result.status())))
                throw new AgentContractException("Expected settled dependency result");
        }
    }

    public void requireScope(UUID run, AgentInputSnapshot snapshot) {
        if (!runId.equals(run) || !snapshotId.toString().equals(snapshot.snapshotId())
                || !contextHash.equals(snapshot.contextHash()) || !projectId.equals(snapshot.projectId())
                || !Objects.equals(routeId, snapshot.routeId())
                || !Objects.equals(anchorNodeId, snapshot.anchorNodeId()))
            throw new AgentContractException("Pending projection scope mismatch");
    }

    public record Dependency(UUID invocationId, UUID ownerRunId, String capabilityId, String deadline) {
        public Dependency {
            if (invocationId == null || ownerRunId == null || capabilityId == null || capabilityId.isBlank()
                    || deadline == null) throw new AgentContractException("Invalid pending dependency");
            try { Instant.parse(deadline); }
            catch (RuntimeException failure) { throw new AgentContractException("Invalid dependency deadline"); }
        }
    }
}
