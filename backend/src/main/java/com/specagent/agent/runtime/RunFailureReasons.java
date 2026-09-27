package com.specagent.agent.runtime;

import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.BrainFailureCode;

import java.util.Map;
import com.specagent.workspace.graph.GraphRuleViolationException;

/**
 * 文件名:RunFailureReasons.java
 *
 * 用途:把抛出的失败统一转换成可持久化的 run 失败记录的唯一入口:
 * 一个稳定的机器码,加上(当原因是用户可干预的模型/Brain 结果时)一段
 * 有边界的可读解释。
 *
 * 机器码保持机器可读,是 eval harness 分类依据;可读文案通过白名单式的
 * 进度摘要暴露给 UI,让它能说明 run 为什么停下,而不是笼统的"生成失败"。
 * 两者都绝不含模型输出、prompt 或 provider 载荷。
 */
public final class RunFailureReasons {

    /** 因 tip 上的回答从未被处理,artifact 生成被拒绝。 */
    public static final String ANSWER_CYCLE_INCOMPLETE = "ANSWER_CYCLE_INCOMPLETE";

    /** 服务重启时该 run 的执行器已消失,启动恢复将其诚实地终态化为失败。 */
    public static final String INTERRUPTED_BY_RESTART = "INTERRUPTED_BY_RESTART";

    /** 执行器租约丢失(数据库重启/租约会话被终止),本进程停止一切写入。 */
    public static final String EXECUTOR_LEASE_LOST = "EXECUTOR_LEASE_LOST";

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
            "该问题已保存回答但后续处理未完成，请先重试该回答，再生成规格",
            INTERRUPTED_BY_RESTART,
            "服务重启导致本次任务中断，未产生结果，可重新发起该操作",
            EXECUTOR_LEASE_LOST,
            "服务执行权已移交，本次结果未提交；重启服务后可重新发起该操作");

    private RunFailureReasons() {
    }

    /** 已知失败码的用户可读文案;未知码返回 null(由 UI 回退到通用文案)。 */
    public static String userCopyFor(String reasonCode) {
        return reasonCode == null ? null : USER_COPY.get(reasonCode);
    }

    /** 失败 run 的稳定机器码;绝不为 null。 */
    public static String reasonCode(RuntimeException failure) {        if (failure instanceof AgentBrainUnavailableException brain) {
            return brain.failureCode().reasonCode();
        }
        if (failure instanceof ExecutorLease.LeaseLostException) {
            return EXECUTOR_LEASE_LOST;
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
     * {@code RUN_FAILED} 事件的 payload。旧的 {@code reason} 字段含义不变
     * (有类型化代码时改存该代码);{@code errorCode} 与 {@code summary} 仅为
     * 有已知文案的失败补充,因此其他所有失败 payload 与之前逐字节一致。
     */
    public static Map<String, Object> payload(String reasonCode) {
        String copy = USER_COPY.get(reasonCode);
        if (copy == null) {
            return Map.of("reason", reasonCode);
        }
        return Map.of("reason", reasonCode, "errorCode", reasonCode, "summary", copy);
    }

    /** 仅为"回答不完整"失败附加安全的恢复定位信息。 */
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
