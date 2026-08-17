package com.specagent.agent.gates;

import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.contracts.SpecDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic validator for a spec draft.
 *
 * <p>Every spec section must be non-blank and carry source references, so the
 * generated spec is always grounded in the exploration context. Unresolved
 * items may exist, but they never replace section source references.
 */
@Component
public class SpecGroundingGate {

    public ReflectionResult validate(SpecDraft draft) {
        List<String> errors = new ArrayList<>();

        if (draft == null) {
            return ReflectionResult.rejectedResult("Spec draft is required");
        }

        for (String sectionName : draft.sections().keySet()) {
            String content = draft.sections().get(sectionName);
            if (content == null || content.isBlank()) {
                errors.add("Spec section content is required: " + sectionName);
                continue;
            }

            List<String> refs = draft.sourceRefsBySection().get(sectionName);
            if (refs == null || refs.isEmpty()) {
                errors.add("Spec section requires source references: " + sectionName);
            }
        }

        if (errors.isEmpty()) {
            return ReflectionResult.acceptedResult();
        }
        return new ReflectionResult(false, errors, List.of());
    }
}