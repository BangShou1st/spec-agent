package com.specagent.context;

import com.specagent.common.Hashes;
import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final ContextSnapshotRepository contextSnapshotRepository;
    private final Json json;

    public ContextBuilder(ProjectRepository projectRepository,
                         RouteRepository routeRepository,
                         NodeRepository nodeRepository,
                         AnswerPatchRepository answerPatchRepository,
                         ContextSnapshotRepository contextSnapshotRepository,
                         Json json,
                         RouteHistoryResolver routeHistoryResolver) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.contextSnapshotRepository = contextSnapshotRepository;
        this.json = json;
        this.routeHistoryResolver = routeHistoryResolver;
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

        if (activeRoute.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Active route is not OPEN: " + activeRouteId
                            + " is " + activeRoute.lifecycleStatus().code());
        }

        // Build lineage from tip to root along parent pointers. A route's
        // context is exactly its root-to-tip lineage; replacement nodes belong
        // to the replacement route's lineage and never enter this chain.
        List<UUID> lineage = resolveLineage(activeRoute.tipNodeId());
        List<UUID> includedNodeIds = new ArrayList<>(lineage);

        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(activeRouteId, includedNodeIds)
                .stream().map(a -> a.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(p -> p.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(r -> r.id())
                .filter(id -> !id.equals(activeRouteId))
                .toList();

        String contextHash = computeHash(operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, null);

        ContextSnapshot snapshot = new ContextSnapshot(Ids.random(), projectId, activeRouteId,
                activeRoute.tipNodeId(), operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, null, contextHash, Instant.now());

        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    public ContextSnapshot buildForRegenerate(UUID projectId,
                                              UUID oldRouteId,
                                              UUID targetNodeId,
                                              UUID replacementRouteId,
                                              UUID replacementNodeId,
                                              String userInstruction) {
        Node targetNode = nodeRepository.findById(targetNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetNodeId));

        // Regenerate context carries only the shared parent lineage of the
        // target node: the target node itself, its answers, patches, and child
        // subtree are deliberately absent.
        List<UUID> parentLineage = resolveLineage(targetNode.parentNodeId());

        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(oldRouteId, parentLineage)
                .stream().map(a -> a.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(p -> p.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(r -> r.id())
                .filter(id -> !id.equals(replacementRouteId))
                .toList();

        Map<String, Object> specialInputsMap = Map.of(
                "oldQuestion", targetNode.question() == null ? "" : targetNode.question(),
                "oldPurpose", targetNode.purpose() == null ? "" : targetNode.purpose(),
                "userInstruction", userInstruction == null ? "" : userInstruction);
        String specialInputs = json.write(specialInputsMap);
        String contextHash = computeHash(ContextOperationType.REGENERATE, parentLineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(Ids.random(), projectId, replacementRouteId,
                replacementNodeId, ContextOperationType.REGENERATE, parentLineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, specialInputs,
                contextHash, Instant.now());

        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * Resolves a route's root-to-tip lineage by following {@code parentNodeId}
     * pointers from the tip upward. The chain is deterministic: a route's
     * context is exactly this chain, and sibling or replacement nodes never
     * appear in it.
     */
    private List<UUID> resolveLineage(UUID tipNodeId) {
        List<UUID> chain = new ArrayList<>();
        UUID current = tipNodeId;
        int guard = 0;
        Set<UUID> seen = new HashSet<>();
        while (current != null && !seen.contains(current)) {
            seen.add(current);
            chain.add(current);
            UUID parent = nodeRepository.findById(current)
                    .map(Node::parentNodeId)
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
                               List<UUID> excludedRouteIds,
                               Map<String, Object> specialInputs) {
        List<UUID> sortedNodes = new ArrayList<>(nodeIds);
        List<UUID> sortedAnswers = new ArrayList<>(answerIds);
        List<UUID> sortedPatches = new ArrayList<>(patchIds);
        List<UUID> sortedExcluded = new ArrayList<>(excludedRouteIds);
        sortedNodes.sort(Comparator.naturalOrder());
        sortedAnswers.sort(Comparator.naturalOrder());
        sortedPatches.sort(Comparator.naturalOrder());
        sortedExcluded.sort(Comparator.naturalOrder());
        Map<String, Object> sortedSpecialInputs = specialInputs == null
                ? Map.of()
                : new TreeMap<>(specialInputs);
        String canonical = operationType.code()
                + "|N:" + sortedNodes
                + "|A:" + sortedAnswers
                + "|P:" + sortedPatches
                + "|X:" + sortedExcluded
                + "|S:" + sortedSpecialInputs;
        return Hashes.sha256Hex(canonical);
    }
}
