package com.specagent.agent.eligibility;

import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.ClaimView;
import com.specagent.agent.contract.LineageEntry;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Java trust-boundary validation for a selected action and its payload. */
public final class ActionEligibilityValidator {

    public void validateSelection(AgentRequestEnvelope request,
                                  ActionProposal proposal,
                                  ActionEligibility eligibility) {
        if (!ActionEligibility.VERSION.equals(eligibility.version())) {
            reject(ActionEligibilityReasonCode.ELIGIBILITY_VERSION_MISMATCH);
        }

        ActionFamily family = ActionFamily.fromCode(proposal.actionFamily());
        if (family == ActionFamily.INVOKE_CAPABILITY) {
            validateCapabilityArguments(request, proposal.payload());
        }

        ActionEligibilityConstraint constraint = eligibility.constraints().get(family.code());
        if (constraint == null || !constraint.eligible()
                || !eligibility.eligibleFamilies().contains(family.code())) {
            ActionEligibilityReasonCode reason = constraint == null
                    || constraint.reasonCodes().isEmpty()
                    ? ActionEligibilityReasonCode.FAMILY_NOT_ELIGIBLE
                    : constraint.reasonCodes().get(0);
            reject(reason);
        }

        switch (family) {
            case CREATE_NODE -> validateCreateNode(request, proposal.payload());
            case REQUEST_USER_INPUT -> validateRequestUserInput(request, proposal.payload());
            case INVOKE_CAPABILITY -> validateVisibleCapability(request, proposal.payload());
            case UPDATE_NODE, CONNECT_NODE, CREATE_ROUTE, RESPOND_TO_USER,
                 GENERATE_ARTIFACT, WAIT -> {
                // No additional payload-dependent eligibility invariant yet.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateCreateNode(AgentRequestEnvelope request, Map<String, Object> payload) {
        Object content = payload.get("content");
        if (!(content instanceof Map<?, ?> contentMap)
                || !(contentMap.get("text") instanceof String contentText)) {
            return; // Structural validator owns malformed payloads.
        }
        String normalized = ActionEligibilityEvaluator.normalizeText(contentText);
        if (normalized.isEmpty()) {
            return;
        }

        if (matchesCurrentAnswer(request, normalized)) {
            reject(ActionEligibilityReasonCode.ANSWER_ALREADY_DURABLE);
        }

        ClaimView duplicateClaim = request.snapshot().effectiveClaims().stream()
                .filter(claim -> normalized.equals(
                        ActionEligibilityEvaluator.normalizeText(claim.text())))
                .findFirst().orElse(null);
        if (duplicateClaim != null) {
            if ("confirmed".equals(duplicateClaim.status())) {
                reject(ActionEligibilityReasonCode.CONFIRMED_STATE_ALREADY_DURABLE);
            }
            reject(ActionEligibilityReasonCode.NO_NEW_DURABLE_UNIT);
        }

        boolean existingNode = request.snapshot().lineage().stream()
                .map(LineageEntry::node)
                .anyMatch(node -> normalized.equals(
                        ActionEligibilityEvaluator.normalizeText(node.body().text())));
        if (existingNode) {
            reject(ActionEligibilityReasonCode.NO_NEW_DURABLE_UNIT);
        }
    }

    private boolean matchesCurrentAnswer(AgentRequestEnvelope request, String normalized) {
        if (normalized.equals(ActionEligibilityEvaluator.normalizeText(
                request.event().freeText()))) {
            return true;
        }
        return request.snapshot().lineage().stream()
                .map(LineageEntry::answer)
                .filter(java.util.Objects::nonNull)
                .anyMatch(answer -> normalized.equals(
                        ActionEligibilityEvaluator.normalizeText(answer.freeText())));
    }

    private void validateRequestUserInput(AgentRequestEnvelope request,
                                          Map<String, Object> payload) {
        Object question = payload.get("questionText");
        if (!(question instanceof String questionText)) {
            return;
        }
        String normalized = ActionEligibilityEvaluator.normalizeText(questionText);
        boolean repeatsAnsweredQuestion = request.snapshot().lineage().stream()
                .filter(entry -> entry.answer() != null)
                .anyMatch(entry -> normalized.equals(
                        ActionEligibilityEvaluator.normalizeText(entry.node().body().text())));
        if (repeatsAnsweredQuestion) {
            reject(ActionEligibilityReasonCode.RESOLVED_BLOCKER);
        }
    }

    private void validateVisibleCapability(AgentRequestEnvelope request,
                                           Map<String, Object> payload) {
        Object id = payload.get("capabilityId");
        boolean visible = id instanceof String capabilityId
                && request.snapshot().availableCapabilities().stream()
                .anyMatch(descriptor -> descriptor.id().equals(capabilityId));
        if (!visible) {
            reject(ActionEligibilityReasonCode.CAPABILITY_NOT_VISIBLE);
        }
    }

    private void validateCapabilityArguments(AgentRequestEnvelope request,
                                             Map<String, Object> payload) {
        Object arguments = payload.get("arguments");
        if (!(arguments instanceof Map<?, ?> map)) {
            return;
        }
        Set<String> allowed = Set.copyOf(request.snapshot().allowedSourceRefs());
        if (containsUngroundedRef(map.values(), allowed)) {
            reject(ActionEligibilityReasonCode.UNGROUNDED_CAPABILITY_ARGUMENT);
        }
    }

    private boolean containsUngroundedRef(Collection<?> values, Set<String> allowed) {
        for (Object value : values) {
            if (value instanceof String text && isRuntimeRef(text) && !allowed.contains(text)) {
                return true;
            }
            if (value instanceof Map<?, ?> nested
                    && containsUngroundedRef(nested.values(), allowed)) {
                return true;
            }
            if (value instanceof List<?> nested
                    && containsUngroundedRef(nested, allowed)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRuntimeRef(String value) {
        return List.of("node:", "answer:", "patch:", "context:", "route:")
                .stream().anyMatch(value::startsWith);
    }

    private static void reject(ActionEligibilityReasonCode reason) {
        throw new ActionIneligibleException(reason);
    }
}
