package com.specagent.agent.contract;

import java.util.List;

/**
 * One ordered lineage entry: a node, its effective answer (if any) and the
 * patches derived from that answer, in the frozen snapshot's own order.
 */
public record LineageEntry(NodeView node, AnswerView answer, List<PatchView> patches) {

    public LineageEntry {
        patches = patches == null ? List.of() : List.copyOf(patches);
    }
}
