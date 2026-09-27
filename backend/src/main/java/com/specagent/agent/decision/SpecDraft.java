package com.specagent.agent.decision;

import java.util.List;
import java.util.Map;

/**
 * 文件名:SpecDraft.java
 *
 * 用途:Agent 循环产出的需求 spec 草稿,包含每个小节的来源引用,
 * 以及仍未消解的条目列表。
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