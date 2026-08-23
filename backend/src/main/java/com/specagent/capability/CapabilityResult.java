package com.specagent.capability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Typed result of one capability invocation. Results are observations with
 * provenance — they enter later decision cycles as external evidence and are
 * never auto-confirmed graph truth.
 */
public record CapabilityResult(
        UUID invocationId,
        String invocationKey,
        String capabilityId,
        Status status,
        Map<String, Object> content,
        List<String> sourceRefs,
        Map<String, Object> provenance,
        List<String> warnings) {

    /**
     * Lifecycle of one invocation. {@code RUNNING} is the persisted claimed-
     * but-unfinished state; {@code IN_PROGRESS} is the typed runtime answer
     * given to callers who arrive while an invocation is still running — it
     * is deliberately not a replay and never carries fabricated content.
     */
    public enum Status { SUCCEEDED, FAILED, REPLAYED, RUNNING, IN_PROGRESS }

    public CapabilityResult {
        content = content == null ? Map.of() : Map.copyOf(content);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static CapabilityResult failed(UUID invocationId, String invocationKey,
                                          String capabilityId, String reason) {
        return new CapabilityResult(invocationId, invocationKey, capabilityId,
                Status.FAILED, Map.of("reason", reason == null ? "" : reason),
                List.of(), Map.of(), List.of());
    }
}
