package com.specagent.node;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Open-but-validated subtype vocabulary per node kind.
 *
 * <p>Adding a subtype is a payload/content concern, not a new action family
 * and not a new business agent. The whitelist keeps model proposals and user
 * input from inventing opaque subtype strings.
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
