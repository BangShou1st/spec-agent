package com.specagent.agent.contract;

import java.util.List;
import java.util.Map;

/**
 * A completed capability invocation exposed to later decision cycles as a
 * provenance-preserving observation. Capability results are external
 * evidence or generated summaries — never auto-confirmed graph truth, and
 * they never enter effective claims on their own.
 */
public record CapabilityResultView(String invocationId,
                                   String capabilityId,
                                   String status,
                                   Map<String, Object> content,
                                   List<String> sourceRefs,
                                   Map<String, Object> provenance) {

    public CapabilityResultView {
        content = content == null ? Map.of() : Map.copyOf(content);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }
}
