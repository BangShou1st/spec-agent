package com.specagent.agent.decision;

import com.specagent.agent.decision.AnswerPatchDraft;
import com.specagent.agent.decision.ReflectionResult;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic validator for an answer patch draft.
 *
 * <p>Confirmed claims must carry full provenance (sourceNodeId + sourceAnswerId)
 * before they may enter requirement state. Assumed or unresolved claims may
 * lack sources for now, but they must never be treated as confirmed spec
 * claims during spec grounding.
 */
@Component
public class PatchReflectionGate {

    public ReflectionResult validate(AnswerPatchDraft draft) {
        List<String> errors = new ArrayList<>();

        if (draft == null) {
            return ReflectionResult.rejectedResult("Answer patch draft is required");
        }

        for (Claim claim : draft.claims()) {
            if (claim == null) {
                errors.add("Patch draft contains null claim");
                continue;
            }

            if (claim.text() == null || claim.text().isBlank()) {
                errors.add("Claim text is required");
            }

            if (claim.status() == ClaimStatus.CONFIRMED) {
                if (claim.sourceNodeId() == null) {
                    errors.add("Confirmed claim requires sourceNodeId");
                }
                if (claim.sourceAnswerId() == null) {
                    errors.add("Confirmed claim requires sourceAnswerId");
                }
            }
        }

        if (errors.isEmpty()) {
            return ReflectionResult.acceptedResult();
        }
        return new ReflectionResult(false, errors, List.of());
    }
}