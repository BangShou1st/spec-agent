package com.specagent.agent.gates;

import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.ReflectionResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic validator for a proposed clarification node.
 *
 * <p>Kept domain-neutral: it checks question shape and answer mechanics, not
 * business content. A node must ask exactly one main question and, when free
 * answers are not allowed, must provide options.
 */
@Component
public class NodeReflectionGate {

    public ReflectionResult validate(NodeDraft draft) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (draft == null) {
            return ReflectionResult.rejectedResult("Node draft is required");
        }

        String question = draft.question();
        if (question == null || question.isBlank()) {
            errors.add("Node question is required");
        } else {
            int questionMarks = countQuestionMarks(question);
            if (questionMarks > 1) {
                errors.add("Node draft must ask one main question");
            }
            if (containsObviousMultipleQuestions(question)) {
                errors.add("Node draft appears to ask multiple questions");
            }
        }

        if (draft.purpose() == null || draft.purpose().isBlank()) {
            warnings.add("Node purpose is missing");
        }

        if (!draft.allowFreeAnswer() && (draft.options() == null || draft.options().isEmpty())) {
            errors.add("Node without free answer must provide options");
        }

        validateOptions(draft.options(), errors);

        if (errors.isEmpty()) {
            return new ReflectionResult(true, List.of(), warnings);
        }
        return new ReflectionResult(false, errors, warnings);
    }

    private int countQuestionMarks(String text) {
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == '?' || c == '？') {
                count++;
            }
        }
        return count;
    }

    private boolean containsObviousMultipleQuestions(String text) {
        String normalized = text.toLowerCase();
        return normalized.contains("? and ")
                || normalized.contains("? also ")
                || normalized.contains(" and why ")
                || normalized.contains(" and how ");
    }

    private void validateOptions(List<com.specagent.node.NodeOption> options,
                                 List<String> errors) {
        if (options == null || options.isEmpty()) {
            return;
        }
        Set<String> normalizedLabels = new HashSet<>();
        for (com.specagent.node.NodeOption option : options) {
            if (option == null || option.label() == null || option.label().isBlank()) {
                errors.add("Node option label must not be blank");
                continue;
            }
            String normalized = option.label().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (!normalizedLabels.add(normalized)) {
                errors.add("Node options must have unique labels");
            }
        }
    }
}
