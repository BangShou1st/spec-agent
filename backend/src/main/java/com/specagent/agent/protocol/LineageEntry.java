package com.specagent.agent.protocol;

import java.util.List;

/**
 * 文件名:LineageEntry.java
 *
 * 用途:冻结快照 lineage(谱系)中的一条有序条目——一个节点、
 * 它的有效回答(如有)以及从该回答派生的补丁,顺序与冻结快照自身
 * 的排列保持一致。
 */
public record LineageEntry(NodeView node, AnswerView answer, List<PatchView> patches) {

    public LineageEntry {
        patches = patches == null ? List.of() : List.copyOf(patches);
    }
}
