package com.specagent.capability;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured, deterministic input to provider/visibility decision making.
 *
 * <p>Only structured facts live here: granted permissions, the context node
 * kinds ("KIND" or "KIND:SUBTYPE") that relevance filtering may match, and
 * bounded scope facts (e.g. explicit user selection, connection availability).
 * Natural-language meaning is never embedded in this record — semantic
 * relevance is the model's or a retrieval stage's job, not a filter input.
 */
public record CapabilityQueryContext(
        Set<String> grantedPermissions,
        List<String> contextKinds,
        Map<String, Object> scopeFacts) {

    public CapabilityQueryContext {
        grantedPermissions = grantedPermissions == null
                ? Set.of() : Set.copyOf(grantedPermissions);
        contextKinds = contextKinds == null ? List.of() : List.copyOf(contextKinds);
        scopeFacts = scopeFacts == null ? Map.of() : Map.copyOf(scopeFacts);
    }

    /** A context with no grants, no context nodes, and no scope facts. */
    public static CapabilityQueryContext empty() {
        return new CapabilityQueryContext(Set.of(), List.of(), Map.of());
    }

    /** A context restricted by permissions only (no relevance scope). */
    public static CapabilityQueryContext forPermissions(Set<String> grantedPermissions) {
        return new CapabilityQueryContext(grantedPermissions, List.of(), Map.of());
    }

    public boolean hasGrant(String permission) {
        return grantedPermissions.contains(permission);
    }
}