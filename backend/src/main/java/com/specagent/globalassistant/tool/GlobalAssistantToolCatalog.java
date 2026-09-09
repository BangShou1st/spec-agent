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
