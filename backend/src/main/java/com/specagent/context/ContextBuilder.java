package com.specagent.context;

import com.specagent.answer.AnswerRepository;
import com.specagent.common.Hashes;
import com.specagent.common.Ids;
import com.specagent.node.NodeRepository;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Builds a deterministic, lineage-based context snapshot for one agent run.
 *
 * <p>Context is not global chat history. It is the active route's tip replayed
 * through its parent lineage to the root, plus answers and patches on that
 * lineage. Sibling routes and superseded/archived/deleted routes are excluded by
 * default and recorded in {@code excludedRouteIds}.
 *
 * <p>This builder is deterministic and never calls a model or model gateway.
 */
@Service
public class ContextBuilder {

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final ContextSnapshotRepository contextSnapshotRepository;

    public ContextBuilder(ProjectRepository projectRepository,
                         RouteRepository routeRepository,
                         NodeRepository nodeRepository,
                         AnswerRepository answerRepository,
                         AnswerPatchRepository answerPatchRepository,
                         ContextSnapshotRepository contextSnapshotRepository) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.contextSnapshotRepository = contextSnapshotRepository;
    }

    public ContextSnapshot buildFromActiveRoute(UUID projectId, UUID agentRunId, ContextOperationType operationType) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        UUID activeRouteId = project.activeRouteId();
        Route activeRoute = routeRepository.findById(activeRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Active route not found: " + activeRouteId));

        List<UUID> lineage = resolveLineage(activeRoute.tipNodeId());
        List<UUID> includedNodeIds = new ArrayList<>(lineage);

        List<UUID> includedAnswerIds = answerRepository.findByRouteAndNodeIds(activeRouteId, includedNodeIds)
                .stream().map(a -> a.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(p -> p.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(r -> r.id())
                .filter(id -> !id.equals(activeRouteId))
                .toList();

        String contextHash = computeHash(operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds);

        ContextSnapshot snapshot = new ContextSnapshot(Ids.random(), projectId, activeRouteId,
                activeRoute.tipNodeId(), operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, null, contextHash, Instant.now());

        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * Walks from the tip node up to the root, returning node ids ordered root to tip.
     */
    private List<UUID> resolveLineage(UUID tipNodeId) {
        List<UUID> chain = new ArrayList<>();
        UUID current = tipNodeId;
        int guard = 0;
        while (current != null) {
            chain.add(current);
            UUID parent = nodeRepository.findById(current)
                    .map(n -> n.parentNodeId())
                    .orElse(null);
            current = parent;
            if (++guard > 10_000) {
                throw new IllegalStateException("Node lineage exceeds maximum depth");
            }
        }
        List<UUID> rootToTip = new ArrayList<>(chain);
        java.util.Collections.reverse(rootToTip);
        return rootToTip;
    }

    private String computeHash(ContextOperationType operationType,
                               List<UUID> nodeIds,
                               List<UUID> answerIds,
                               List<UUID> patchIds,
                               List<UUID> excludedRouteIds) {
        List<UUID> sortedNodes = new ArrayList<>(nodeIds);
        List<UUID> sortedAnswers = new ArrayList<>(answerIds);
        List<UUID> sortedPatches = new ArrayList<>(patchIds);
        List<UUID> sortedExcluded = new ArrayList<>(excludedRouteIds);
        sortedNodes.sort(Comparator.naturalOrder());
        sortedAnswers.sort(Comparator.naturalOrder());
        sortedPatches.sort(Comparator.naturalOrder());
        sortedExcluded.sort(Comparator.naturalOrder());
        String canonical = operationType.code()
                + "|N:" + sortedNodes
                + "|A:" + sortedAnswers
                + "|P:" + sortedPatches
                + "|X:" + sortedExcluded;
        return Hashes.sha256Hex(canonical);
    }
}
