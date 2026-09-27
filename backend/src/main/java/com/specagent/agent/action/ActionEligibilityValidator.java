package com.specagent.agent.action;

import com.specagent.agent.protocol.ActionIneligibleException;

import com.specagent.agent.action.ActionEligibilityValidator;
import com.specagent.agent.protocol.ActionEligibility;
import com.specagent.agent.protocol.ActionEligibilityConstraint;
import com.specagent.agent.protocol.ActionEligibilityReasonCode;

import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.ClaimView;
import com.specagent.agent.protocol.LineageEntry;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:ActionEligibilityValidator.java
 *
 * 用途:Java 信任边界内的资格校验器,验证模型选中的动作及其载荷。
 * 在资格掩码的基础上做更深的确定性校验:重复的持久化单元
 * (已落库的 Answer/confirmed claim/已有节点)、未被解析的提问、
 * 能力可见性、能力参数中的未 grounding 引用、DECISION 节点的
 * 类型化持久化意图等。
 *
 * 协作:由 ActionEligibilityGate.assess 调用;任何规则不满足
 * 即抛 ActionIneligibleException(带原因码)。
 */
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
                // 这些动作族尚无额外的载荷相关资格不变量。
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateCreateNode(AgentRequestEnvelope request, Map<String, Object> payload) {
        Object content = payload.get("content");
        if (!(content instanceof Map<?, ?> contentMap)
                || !(contentMap.get("text") instanceof String contentText)) {
            return; // 结构合法性由结构校验器负责。
        }
        String normalized = ActionEligibilityEvaluator.normalizeText(contentText);
        if (normalized.isEmpty()) {
            return;
        }

        if (matchesCurrentAnswer(request, normalized)) {
            reject(ActionEligibilityReasonCode.ANSWER_ALREADY_DURABLE);
        }

        String subtype = payload.get("subtype") instanceof String value ? value : "";
        if ("NOTE".equals(subtype) && containsCurrentAnswer(request, normalized)) {
            // 精确的规范化包含判断,而非模糊相似:NOTE 不允许把已经落库的
            // Answer 包裹在解释性套话里重新提交。
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

        if ("DECISION".equals(subtype)
                && !("ANSWER_SUBMITTED".equals(request.event().kind())
                && request.event().persistenceIntent()
                == AgentEvent.PersistenceIntent.RECORD_DECISION_NODE)) {
            // 自然语言的 freeText 不构成权威。持久化的 DECISION 必须有
            // 上述 Runtime 持有的显式事件意图。
            reject(ActionEligibilityReasonCode.MISSING_TYPED_PERSISTENCE_INTENT);
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

    private boolean containsCurrentAnswer(AgentRequestEnvelope request, String normalizedContent) {
        List<String> answers = new java.util.ArrayList<>();
        String eventAnswer = ActionEligibilityEvaluator.normalizeText(request.event().freeText());
        if (!eventAnswer.isEmpty()) {
            answers.add(eventAnswer);
        }
        request.snapshot().lineage().stream()
                .map(LineageEntry::answer)
                .filter(java.util.Objects::nonNull)
                .map(answer -> ActionEligibilityEvaluator.normalizeText(answer.freeText()))
                .filter(answer -> !answer.isEmpty())
                .forEach(answers::add);
        return answers.stream().anyMatch(normalizedContent::contains);
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
