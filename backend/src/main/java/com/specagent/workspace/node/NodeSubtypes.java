package com.specagent.workspace.node;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:NodeSubtypes.java
 *
 * 用途:每种节点 kind 的"开放但经校验"的 subtype 词表。新增一个
 * subtype 属于负载/内容层面的事,不是新的动作族、也不是新的业务
 * agent。这份白名单防止模型提案和用户输入发明不透明的 subtype 字符串。
 */
public final class NodeSubtypes {

    private static final Map<NodeKind, Set<String>> ALLOWED = Map.of(
            NodeKind.KNOWLEDGE, Set.of("IDEA", "NOTE", "REQUIREMENT", "DECISION", "RISK", "ASSUMPTION"),
            NodeKind.INTERACTION, Set.of("QUESTION"),
            NodeKind.RESOURCE, Set.of("FILE", "IMAGE", "URL", "REPOSITORY", "API_DOCUMENTATION", "TEXT"),
            NodeKind.ARTIFACT, Set.of("SUMMARY", "SPEC", "REPORT"));

    private NodeSubtypes() {
    }

    public static boolean isAllowed(NodeKind kind, String subtype) {
        Set<String> allowed = ALLOWED.get(kind);
        return allowed != null && subtype != null && allowed.contains(normalize(subtype));
    }

    public static String requireAllowed(NodeKind kind, String subtype) {
        if (!isAllowed(kind, subtype)) {
            throw new IllegalArgumentException(
                    "Subtype '" + subtype + "' is not allowed for node kind " + kind.code()
                            + "; allowed: " + List.copyOf(ALLOWED.getOrDefault(kind, Set.of())));
        }
        return normalize(subtype);
    }

    public static String normalize(String subtype) {
        return subtype == null ? null : subtype.trim().toUpperCase();
    }
}
