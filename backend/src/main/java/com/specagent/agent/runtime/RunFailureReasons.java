package com.specagent.agent.runtime;

import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.BrainFailureCode;

import java.util.Map;
import com.specagent.graph.GraphRuleViolationException;

/**
 * One place that turns a thrown failure into the durable run-failure record:
 * a stable machine code plus, when the cause is a model/brain outcome the user
 * can act on, a bounded readable explanation.
 *
 * <p>Codes stay machine-readable and are what the eval harness classifies; the
 * readable copy is exposed through the whitelisted progress summary so the UI
 * can say <em>why</em> a run stopped instead of a generic "generation failed".
 * Neither ever contains model output, prompts, or provider payloads.
 */
public final class RunFailureReasons {

    /** Artifact generation refused because the tip answer was never processed. */
    public static final String ANSWER_CYCLE_INCOMPLETE = "ANSWER_CYCLE_INCOMPLETE";

    private static final Map<String, String> USER_COPY = Map.of(
            BrainFailureCode.MODEL_CONTRACT_VIOLATION.reasonCode(),
            "模型输出未通过校验，本次生成未完成，可直接重试",
            BrainFailureCode.MODEL_UNGROUNDED_REFERENCE.reasonCode(),
            "模型引用了本次上下文之外的依据，已拦截，可直接重试",
            BrainFailureCode.BRAIN_TIMEOUT.reasonCode(),
            "模型服务响应超时，本次生成未完成，请稍后重试",
            BrainFailureCode.BRAIN_UNAVAILABLE.reasonCode(),
            "模型服务暂时不可用，本次生成未完成，请稍后重试",
            BrainFailureCode.MODEL_PROVIDER_FAILURE.reasonCode(),
            "模型服务调用失败，本次生成未完成，请稍后重试",
            ANSWER_CYCLE_INCOMPLETE,
            "该问题已保存回答但后续处理未完成，请先重试该回答，再生成规格");

    private RunFailureReasons() {
    }

    /** Stable machine code for a failed run; never null. */
    public static String reasonCode(RuntimeException failure) {
        if (failure instanceof AgentBrainUnavailableException brain) {
            return brain.failureCode().reasonCode();
        }
        if (failure instanceof IncompleteAnswerCycleException) {
            return ANSWER_CYCLE_INCOMPLETE;
        }
        if (failure instanceof GraphRuleViolationException graph
                && ANSWER_CYCLE_INCOMPLETE.equals(graph.code())) {
            return ANSWER_CYCLE_INCOMPLETE;
        }
        return failure.getClass().getSimpleName();
    }

    /**
     * {@code RUN_FAILED} payload. The legacy {@code reason} field keeps its
     * meaning (now carrying the typed code where one exists); {@code errorCode}
     * and {@code summary} are added only for failures with known copy, so every
     * other failure payload is byte-identical to what it was before.
     */
    public static Map<String, Object> payload(String reasonCode) {
        String copy = USER_COPY.get(reasonCode);
        if (copy == null) {
            return Map.of("reason", reasonCode);
        }
        return Map.of("reason", reasonCode, "errorCode", reasonCode, "summary", copy);
    }

    /** Adds only safe recovery identity to the incomplete-answer failure. */
    public static Map<String, Object> payload(RuntimeException failure) {
        String reason = reasonCode(failure);
        if (!(failure instanceof IncompleteAnswerCycleException incomplete)
                || incomplete.answerId() == null
                || incomplete.routeId() == null
                || incomplete.nodeId() == null) {
            return payload(reason);
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>(payload(reason));
        payload.put("recoveryAnswerId", incomplete.answerId().toString());
        payload.put("recoveryRouteId", incomplete.routeId().toString());
        payload.put("recoveryNodeId", incomplete.nodeId().toString());
        return Map.copyOf(payload);
    }
}
