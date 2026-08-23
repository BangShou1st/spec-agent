package com.specagent.capability;

import java.util.List;
import java.util.Map;

/**
 * The bounded, runtime-owned description of one capability as exposed to the
 * planner. Descriptors are filtered by permissions and context relevance
 * before model exposure; the planner never sees implementation classes,
 * SDK clients, or credentials.
 */
public record CapabilityDescriptor(
        String capabilityId,
        String version,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        boolean readOnly,
        SideEffectClass sideEffectClass,
        List<String> requiredPermissions,
        List<String> supports) {

    public CapabilityDescriptor {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
        supports = supports == null ? List.of() : List.copyOf(supports);
    }
}
