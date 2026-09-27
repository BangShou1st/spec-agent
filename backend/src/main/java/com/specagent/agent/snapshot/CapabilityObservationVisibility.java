package com.specagent.agent.snapshot;

import com.specagent.capability.CapabilityInvocationRecord;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:CapabilityObservationVisibility.java
 *
 * 用途:能力观察(capability observations)的路由血缘可见性策略。
 *
 * 能力结果只是观察:本策略决定一条路由可以看到哪些已存储的结果,
 * 从不创建节点、claim、决策或图拓扑。可见性遵循"可能产生该观察的
 * 血缘",而不是简单的路由 id 相等:锚定在共享前缀中的结果对所有
 * 继承分支保持可见,分支私有的结果则留在自己的路由上。
 *
 * 无法归因的记录保持隐藏(fail-closed):当发起调用的 run 和任何
 * 节点引用都无法把结果与当前血缘关联起来时,它不会进入 agent 输入。
 */
public final class CapabilityObservationVisibility {

    private CapabilityObservationVisibility() {
    }

    /** 发起调用的 run 的路由与输入节点归属(若已知)。 */
    public record RunAttribution(UUID routeId, UUID inputNodeId) {
    }

    /**
     * 收集该观察可证明指向的全部节点:持久化结果 source refs 中的节点引用,
     * 加上调用参数中出现的任何节点引用。格式非法的引用直接忽略,不视为致命。
     */
    public static Set<UUID> referencedNodeIds(CapabilityInvocationRecord record) {
        Set<UUID> refs = new LinkedHashSet<>();
        if (record.result() != null) {
            collectNodeRefs(record.result().get("sourceRefs"), refs);
        }
        collectNodeRefs(record.arguments(), refs);
        return Set.copyOf(refs);
    }

    private static void collectNodeRefs(Object value, Set<UUID> refs) {
        if (value instanceof String ref && ref.startsWith("node:")) {
            try {
                refs.add(UUID.fromString(ref.substring(5)));
            } catch (IllegalArgumentException expected) {
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object entry : map.values()) {
                collectNodeRefs(entry, refs);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                collectNodeRefs(entry, refs);
            }
        }
    }

    /**
     * 判断观察是否属于给定的路由上下文。本路由的结果永远可见;否则
     * 所有被引用节点必须都落在当前血缘上。无引用的结果回退到发起 run
     * 的输入节点;完全无法归因的记录保持隐藏。无路由查询的情况
     * (路由 id 为 null)只通过血缘成员关系匹配,绝不通过 null 路由相等
     * 匹配,因此两个游离锚点之间看不到彼此的观察。
     */
    public static boolean isVisible(UUID snapshotRouteId,
                                      Set<UUID> lineageNodeIds,
                                      RunAttribution run,
                                      Set<UUID> referencedNodeIds) {
        if (snapshotRouteId != null && run != null && run.routeId() != null
                && run.routeId().equals(snapshotRouteId)) {
            return true;
        }
        if (!referencedNodeIds.isEmpty()) {
            return lineageNodeIds.containsAll(referencedNodeIds);
        }
        if (run != null && run.inputNodeId() != null) {
            return lineageNodeIds.contains(run.inputNodeId());
        }
        return false;
    }
}
