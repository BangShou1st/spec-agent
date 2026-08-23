package com.specagent.agent.contract;

/**
 * A capability descriptor the brain may reference. Filtered by permission
 * and context relevance Java-side before exposure; descriptors carry only
 * bounded metadata — never implementation classes, endpoints, or
 * credentials.
 */
public record CapabilityDescriptor(String id,
                                   String version,
                                   String description,
                                   boolean readOnly,
                                   String sideEffectClass) {
}
