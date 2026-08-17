package com.specagent.agent.contracts;

import java.util.List;
import java.util.Map;

/**
 * Draft of a requirements spec produced by the agent loop, with per-section
 * source references and a list of items still unresolved.
 */
public record SpecDraft(
        Map<String, String> sections,
        List<String> unresolvedItems,
        Map<String, List<String>> sourceRefsBySection
) {
    public SpecDraft {
        sections = sections == null ? Map.of() : Map.copyOf(sections);
        unresolvedItems = unresolvedItems == null ? List.of() : List.copyOf(unresolvedItems);
        sourceRefsBySection = sourceRefsBySection == null ? Map.of() : Map.copyOf(sourceRefsBySection);
    }
}