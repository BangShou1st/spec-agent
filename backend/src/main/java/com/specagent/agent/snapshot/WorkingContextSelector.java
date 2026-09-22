package com.specagent.agent.snapshot;

import com.specagent.node.Node;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Selects the bounded model-facing working window from the full immutable
 * ContextSnapshot manifest. Route lineage and derived material have different
 * priorities: derived nodes may fill unused capacity, but never displace the
 * current route tip or the recent route lineage.
 */
@Component
public class WorkingContextSelector {

    public static final int MAX_WORKING_LINEAGE_ENTRIES = 12;
    public static final int MAX_WORKING_LINEAGE_CHARS = 12_000;

    /** Compatibility aliases for callers that used the original names. */
    public static final int MAX_LINEAGE_ENTRIES = MAX_WORKING_LINEAGE_ENTRIES;
    public static final int MAX_LINEAGE_CHARS = MAX_WORKING_LINEAGE_CHARS;

    /**
     * Selects mandatory anchors first, then the newest route lineage, and only
     * then bounded derived material. {@code modelFacingChars} must measure the
     * actual wire projection of the supplied ordered Node list; this keeps the
     * budget at the Brain boundary instead of estimating canonical Nodes.
     */
    public List<Node> select(List<Node> canonicalRouteLineage,
                             List<Node> derivedMaterial,
                             Set<UUID> mandatoryNodeIds,
                             Function<List<Node>, Integer> modelFacingChars) {
        List<Node> ordered = distinct(canonicalRouteLineage, derivedMaterial);
        if (ordered.isEmpty()) {
            return List.of();
        }
        Set<UUID> mandatory = mandatoryNodeIds == null
                ? Set.of() : Set.copyOf(mandatoryNodeIds);
        Function<List<Node>, Integer> estimator = modelFacingChars == null
                ? ignored -> 0 : modelFacingChars;

        LinkedHashSet<UUID> selectedIds = new LinkedHashSet<>();
        // Mandatory nodes are never budget-evicted. In normal snapshots this is
        // the current tip plus at most one event anchor.
        for (Node node : ordered) {
            if (mandatory.contains(node.id())) {
                selectedIds.add(node.id());
            }
        }

        addRecentCandidates(selectedIds, canonicalRouteLineage, ordered, estimator);
        addRecentCandidates(selectedIds, derivedMaterial, ordered, estimator);

        return ordered.stream()
                .filter(node -> selectedIds.contains(node.id()))
                .toList();
    }

    /** Simple node-only projection retained for small non-runtime callers. */
    public List<Node> select(List<Node> canonicalLineage) {
        return select(canonicalLineage, List.of(), Set.of(), this::estimateNodeOnlyChars);
    }

    private void addRecentCandidates(LinkedHashSet<UUID> selectedIds,
                                     List<Node> candidates,
                                     List<Node> ordered,
                                     Function<List<Node>, Integer> estimator) {
        if (candidates == null) {
            return;
        }
        for (int index = candidates.size() - 1; index >= 0; index--) {
            if (selectedIds.size() >= MAX_WORKING_LINEAGE_ENTRIES) {
                return;
            }
            Node candidate = candidates.get(index);
            if (candidate == null || selectedIds.contains(candidate.id())) {
                continue;
            }
            selectedIds.add(candidate.id());
            List<Node> projected = ordered.stream()
                    .filter(node -> selectedIds.contains(node.id()))
                    .toList();
            if (estimator.apply(projected) <= MAX_WORKING_LINEAGE_CHARS) {
                continue;
            }
            selectedIds.remove(candidate.id());
        }
    }

    private List<Node> distinct(List<Node> canonicalRouteLineage,
                                List<Node> derivedMaterial) {
        Map<UUID, Node> byId = new LinkedHashMap<>();
        if (canonicalRouteLineage != null) {
            canonicalRouteLineage.stream().filter(node -> node != null)
                    .forEach(node -> byId.putIfAbsent(node.id(), node));
        }
        if (derivedMaterial != null) {
            derivedMaterial.stream().filter(node -> node != null)
                    .forEach(node -> byId.putIfAbsent(node.id(), node));
        }
        return List.copyOf(byId.values());
    }

    private int estimateNodeOnlyChars(List<Node> nodes) {
        int chars = 2;
        for (Node node : nodes) {
            chars += (node.question() == null ? 0 : node.question().length())
                    + (node.purpose() == null ? 0 : node.purpose().length())
                    + (node.contentText() == null ? 0 : node.contentText().length())
                    + 64;
        }
        return chars;
    }
}
