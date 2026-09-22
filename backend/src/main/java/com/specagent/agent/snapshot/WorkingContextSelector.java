package com.specagent.agent.snapshot;

import com.specagent.node.Node;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Selects the bounded model-facing working window from the full immutable
 * ContextSnapshot manifest. The manifest remains the audit/recovery truth;
 * this selector only controls what crosses the Brain boundary.
 */
@Component
public class WorkingContextSelector {

    public static final int MAX_LINEAGE_ENTRIES = 12;
    public static final int MAX_LINEAGE_CHARS = 12_000;

    public List<Node> select(List<Node> canonicalLineage) {
        if (canonicalLineage == null || canonicalLineage.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, canonicalLineage.size() - MAX_LINEAGE_ENTRIES);
        List<Node> recent = canonicalLineage.subList(start, canonicalLineage.size());
        int chars = 0;
        int first = recent.size();
        for (int index = recent.size() - 1; index >= 0; index--) {
            int nodeChars = estimateChars(recent.get(index));
            if (first < recent.size() && chars + nodeChars > MAX_LINEAGE_CHARS) {
                break;
            }
            chars += nodeChars;
            first = index;
        }
        return List.copyOf(recent.subList(first, recent.size()));
    }

    private int estimateChars(Node node) {
        String question = node.question() == null ? "" : node.question();
        String purpose = node.purpose() == null ? "" : node.purpose();
        String content = node.contentText() == null ? "" : node.contentText();
        return question.length() + purpose.length() + content.length() + 64;
    }
}
