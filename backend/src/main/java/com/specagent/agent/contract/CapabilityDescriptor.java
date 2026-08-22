package com.specagent.agent.contract;

/**
 * A capability descriptor the brain may reference. Filtered by permission
 * Java-side before exposure; Stage A always sends an empty list.
 */
public record CapabilityDescriptor(String id,
                                   String version,
                                   boolean readOnly) {
}
