package com.specagent.agent.eligibility;

import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.ClaimView;
import com.specagent.agent.contract.LineageEntry;
import com.specagent.common.Hashes;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Runtime-owned evaluator for deterministic necessary conditions and
 * hard prohibitions. It deliberately does not implement semantic ranking.
 *
 * <p>Frozen principle: constrain execution, not reasoning. Unresolved
 * conflicts and open questions never restrict the eligible action set here.
 * They must still be surfaced faithfully in {@code observation.conflicts},
 * but the ACTION choice stays unrestricted at this boundary. Execution
 * safety belongs to the capability-visibility, WAIT-dependency, duplicate,
 * schema, refs, stale, policy, permission, and graph-invariant gates —
 * never to a semantic conflict/open-question mapping.
 */
public final class ActionEligibilityEvaluator {

    public ActionEligibility evaluate(AgentRequestEnvelope request) {
        EnumMap<ActionFamily, ActionEligibilityConstraint> evaluated =
                new EnumMap<>(ActionFamily.class);
        for (ActionFamily family : ActionFamily.values()) {
            evaluated.put(family, ActionEligibilityConstraint.allow());
        }

        // No pending-dependency representation exists in agent-input.v2. WAIT
        // therefore cannot acquire deterministic eligibility from this input.
        evaluated.put(ActionFamily.WAIT, ActionEligibilityConstraint.deny(
                ActionEligibilityReasonCode.NO_PENDING_DEPENDENCY));

        if (request.snapshot().availableCapabilities().isEmpty()) {
            evaluated.put(ActionFamily.INVOKE_CAPABILITY, ActionEligibilityConstraint.deny(
                    ActionEligibilityReasonCode.CAPABILITY_NOT_VISIBLE));
        }

        Map<String, ActionEligibilityConstraint> constraints = new LinkedHashMap<>();
        List<String> eligibleFamilies = new ArrayList<>();
        for (ActionFamily family : ActionFamily.values()) {
            ActionEligibilityConstraint constraint = evaluated.get(family);
            constraints.put(family.code(), constraint);
            if (constraint.eligible()) {
                eligibleFamilies.add(family.code());
            }
        }

        return new ActionEligibility(
                ActionEligibility.VERSION,
                eligibleFamilies,
                constraints,
                basisHash(request, evaluated));
    }

    /**
     * Stable semantic identity: Runtime UUIDs and collection order are
     * intentionally excluded, while every fact used by this evaluator is
     * included in normalized sorted form.
     */
    private String basisHash(AgentRequestEnvelope request,
                             EnumMap<ActionFamily, ActionEligibilityConstraint> evaluated) {
        List<String> facts = new ArrayList<>();
        facts.add("event:" + request.event().kind());
        facts.add("hasEventText:" + hasText(request.event().freeText()));
        facts.add("persistenceIntent:" + String.valueOf(request.event().persistenceIntent()));
        for (ClaimView claim : request.snapshot().effectiveClaims()) {
            facts.add("claim:" + claim.kind() + ":" + claim.status() + ":"
                    + normalizeText(claim.text()));
        }
        for (LineageEntry entry : request.snapshot().lineage()) {
            facts.add("node:" + entry.node().kind() + ":"
                    + normalizeText(entry.node().body().text()) + ":answered="
                    + (entry.answer() != null));
            if (entry.answer() != null) {
                facts.add("answer:" + normalizeText(entry.answer().freeText()));
            }
        }
        request.snapshot().availableCapabilities().forEach(capability ->
                facts.add("capability:" + capability.id() + ":" + capability.version()
                        + ":" + capability.sideEffectClass()));
        evaluated.forEach((family, constraint) -> facts.add(
                "constraint:" + family.code() + ":" + constraint.eligible() + ":"
                        + constraint.reasonCodes().stream().map(Enum::name).sorted().toList()));
        facts.sort(String::compareTo);
        return Hashes.sha256Hex(String.join("\n", facts));
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
