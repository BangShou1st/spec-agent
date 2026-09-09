package com.specagent.skill.discovery;

import java.util.List;
import java.util.Map;

/**
 * Deterministic discovery input derived from the frozen model context. Only
 * structured facts live here (never raw user wording, which would invite
 * lexical routing): current goal/operation hints, resource kinds already in
 * context, and bounded recent capability observations.
 *
 * <p>The optional {@code searchQuery} carries an explicit model-authored
 * search string for the {@code skill.search} fallback path only. It is never
 * populated from ambient user text by the runtime — only the model's own
 * search-tool argument flows here — so generic token matching cannot become
 * covert keyword routing.
 */
public record SkillDiscoveryContext(
        String operation,
        List<String> resourceKinds,
        List<String> recentCapabilityIds,
        Map<String, Object> scopeFacts,
        String searchQuery) {

    public SkillDiscoveryContext {
        resourceKinds = resourceKinds == null ? List.of() : List.copyOf(resourceKinds);
        recentCapabilityIds = recentCapabilityIds == null
                ? List.of() : List.copyOf(recentCapabilityIds);
        scopeFacts = scopeFacts == null ? Map.of() : Map.copyOf(scopeFacts);
    }

    /** Legacy constructor for callers without an explicit search query. */
    public SkillDiscoveryContext(String operation,
                                 List<String> resourceKinds,
                                 List<String> recentCapabilityIds,
                                 Map<String, Object> scopeFacts) {
        this(operation, resourceKinds, recentCapabilityIds, scopeFacts, null);
    }

    public static SkillDiscoveryContext empty() {
        return new SkillDiscoveryContext(null, List.of(), List.of(), Map.of());
    }

    /** Explicit search query for the fallback path; null when not searching. */
    public static SkillDiscoveryContext forSearch(String query) {
        return new SkillDiscoveryContext(null, List.of(), List.of(), Map.of(), query);
    }
}