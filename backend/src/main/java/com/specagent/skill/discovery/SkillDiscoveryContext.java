package com.specagent.skill.discovery;

import java.util.List;
import java.util.Map;

/**
 * Deterministic discovery input derived from the frozen model context. Only
 * structured facts live here (never raw user wording, which would invite
 * lexical routing): current goal/operation hints, resource kinds already in
 * context, and bounded recent capability observations.
 */
public record SkillDiscoveryContext(
        String operation,
        List<String> resourceKinds,
        List<String> recentCapabilityIds,
        Map<String, Object> scopeFacts) {

    public SkillDiscoveryContext {
        resourceKinds = resourceKinds == null ? List.of() : List.copyOf(resourceKinds);
        recentCapabilityIds = recentCapabilityIds == null
                ? List.of() : List.copyOf(recentCapabilityIds);
        scopeFacts = scopeFacts == null ? Map.of() : Map.copyOf(scopeFacts);
    }

    public static SkillDiscoveryContext empty() {
        return new SkillDiscoveryContext(null, List.of(), List.of(), Map.of());
    }
}