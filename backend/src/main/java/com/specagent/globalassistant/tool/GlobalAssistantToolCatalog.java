package com.specagent.globalassistant.tool;

import com.specagent.capability.CapabilityQueryContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Product capability boundary for Global Assistant V1.
 * Explicit whitelist over the shared CapabilityRegistry, plus the
 * APPLICATION:GLOBAL_ASSISTANT context marker for double isolation.
 */
public final class GlobalAssistantToolCatalog {
    private GlobalAssistantToolCatalog() {
    }
    public static final String SUPPORT_MARKER = "APPLICATION:GLOBAL_ASSISTANT";
    public static final List<String> TOOL_IDS = List.of(
            ProjectCreateCapability.CAPABILITY_ID,
            ProjectSearchCapability.CAPABILITY_ID,
            ProjectListRecentCapability.CAPABILITY_ID,
            ProjectGetSummaryCapability.CAPABILITY_ID);
    public static final String FINGERPRINT = "ga-v1:project.create,project.search,project.list_recent,project.get_summary";
    public static boolean isAllowed(String capabilityId) {
        return TOOL_IDS.contains(capabilityId);
    }
    /**
     * Exact V1 argument contract per tool. Unknown keys are always rejected
     * by the decision validator; adapters apply the same allowlist as a
     * defensive second gate without duplicating value validation.
     */
    public static java.util.Set<String> allowedArguments(String capabilityId) {
        if (ProjectCreateCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("title");
        }
        if (ProjectSearchCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("query", "limit");
        }
        if (ProjectListRecentCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("limit");
        }
        if (ProjectGetSummaryCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("projectId");
        }
        return null;
    }
    /**
     * Generic integral check over the JDK numeric types Jackson produces.
     * Only true integer types in [1,10] pass; fractional values, numeric
     * strings and booleans never pass, regardless of their intValue.
     */
    public static boolean isValidLimit(Object raw) {
        if (raw == null) {
            return true;
        }
        long value;
        if (raw instanceof Integer number) {
            value = number;
        } else if (raw instanceof Long number) {
            value = number;
        } else if (raw instanceof Short number) {
            value = number;
        } else if (raw instanceof Byte number) {
            value = number;
        } else if (raw instanceof java.math.BigInteger number) {
            return number.compareTo(java.math.BigInteger.ONE) >= 0
                    && number.compareTo(java.math.BigInteger.TEN) <= 0;
        } else {
            return false;
        }
        return value >= 1 && value <= 10;
    }
    public static int limitOrDefault(Object raw, int fallback) {
        if (raw instanceof Integer number) {
            return number;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }
    public static CapabilityQueryContext queryContext(Set<String> grantedPermissions) {
        return new CapabilityQueryContext(
                grantedPermissions == null ? Set.of() : grantedPermissions,
                List.of(SUPPORT_MARKER),
                Map.of());
    }
    public static CapabilityQueryContext queryContext() {
        return queryContext(Set.of());
    }
}
