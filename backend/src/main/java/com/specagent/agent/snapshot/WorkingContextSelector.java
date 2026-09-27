package com.specagent.agent.snapshot;

import com.specagent.workspace.node.Node;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 文件名:WorkingContextSelector.java
 *
 * 用途:从完整且不可变的 ContextSnapshot 清单中,选出有界的、面向模型的
 * 工作上下文窗口。路由血缘与派生材料的优先级不同:派生节点只能填补剩余
 * 容量,绝不能挤掉当前路由 tip 或最近的路线血缘。
 *
 * 协作:由 AgentInputSnapshotBuilder 在首次冻结投影时调用,决定血缘
 * (lineage)里实际进入模型输入的节点集合。
 */
@Component
public class WorkingContextSelector {

    public static final int MAX_WORKING_LINEAGE_ENTRIES = 12;
    public static final int MAX_WORKING_LINEAGE_CHARS = 12_000;

    /** 兼容别名,供使用旧命名的调用方继续使用。 */
    public static final int MAX_LINEAGE_ENTRIES = MAX_WORKING_LINEAGE_ENTRIES;
    public static final int MAX_LINEAGE_CHARS = MAX_WORKING_LINEAGE_CHARS;

    /**
     * 先选必选锚点,再选最新的路由血缘,最后才补充有界的派生材料。
     * {@code modelFacingChars} 必须测量给定有序 Node 列表实际投影到
     * wire 上的体积;这样预算就卡在 Brain 边界上,而不是对规范化
     * Node 做粗略估算。
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
        // 必选节点永远不会因预算被淘汰。在正常 snapshot 里,
        // 它就是当前 tip 加上至多一个事件锚点。
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
