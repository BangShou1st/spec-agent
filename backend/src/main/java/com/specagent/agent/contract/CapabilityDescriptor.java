package com.specagent.agent.contract;

import java.util.List;
import java.util.Map;

/**
 * A capability descriptor the brain may reference. Filtered by permission
 * and context relevance Java-side before exposure; descriptors carry only
 * bounded metadata — never implementation classes, endpoints, or
 * credentials.
 *
 * <p>Both {@code inputSchema} and {@code supports} are optional for wire and
 * replay compatibility: absent fields (legacy frozen payloads, older brains)
 * round-trip as empty collections rather than breaking strict parsing.
 * {@code inputSchema} carries a bounded JSON-Schema-flavoured argument shape
 * so the model can construct valid calls for dynamic providers (e.g. MCP
 * tools); {@code supports} mirrors the runtime relevance facts
 * ("KIND" or "KIND:SUBTYPE") that drove visibility.
 */
public record CapabilityDescriptor(String id,
                                   String version,
                                   String description,
                                   Map<String, Object> inputSchema,
                                   boolean readOnly,
                                   String sideEffectClass,
                                   List<String> supports) {

    public CapabilityDescriptor {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        supports = supports == null ? List.of() : List.copyOf(supports);
    }

    /** Legacy constructor for callers that predate the bounded schema fields. */
    public CapabilityDescriptor(String id,
                                String version,
                                String description,
                                boolean readOnly,
                                String sideEffectClass) {
        this(id, version, description, Map.of(), readOnly, sideEffectClass, List.of());
    }
}
