package com.specagent.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件名:P3ADecisionScoring.java
 *
 * 用途:P3-A C 层计分的**确定性**核心:把四个此前被混为一谈的量严格分开。
 *
 * 背景(上次代码审查确认的问题):live 套件把
 * {@code payloadValid = "completed".equals(result) || actionAcceptable},
 * 汇总时又把 {@code payloadValidity} 直接取 {@code actionAccuracy}——于是
 * "动作对不对" 与 "payload 协议是否合法" 无法区分,且模型输出契约违约被
 * 归并到网络 INFRA,掩盖了真实的模型协议质量缺陷。
 *
 * 本类只做纯计算,不依赖 Spring / 数据库 / 模型:
 * <ol>
 *   <li><b>Action acceptable-set accuracy</b> —— 选中的动作族是否落在场景
 *       声明的 acceptable 集合内(仅在动作确实产出时可测);</li>
 *   <li><b>Payload schema validity</b> —— DECISION payload 是否通过 Java
 *       契约校验(独立于动作对错);</li>
 *   <li><b>Runtime execution success</b> —— 运行时是否真正执行成功;</li>
 *   <li><b>INFRA failures</b> —— 真正的网络/provider 故障,不计为决策错误。</li>
 * </ol>
 *
 * payload 有效性的判定信号是**真实**的:运行时在构造提案之前调用
 * {@code AgentBrainResponseValidator.validateDecision}(含 payload 形状校验),
 * 通过才会写出 {@code PROPOSAL_CREATED}(因此 {@code actualAction != null});
 * 违约则 run 以契约错误 fail-closed。信息不足时标记
 * {@link PayloadValidity#NOT_MEASURED},绝不伪造通过率。
 */
public final class P3ADecisionScoring {

    private P3ADecisionScoring() {
    }

    /** payload 协议有效性的三态。 */
    public enum PayloadValidity {
        VALID,
        INVALID,
        NOT_MEASURED
    }

    /**
     * 单个场景的 C 层观察(已由调用方完成 INFRA 判定与协议分类)。
     *
     * @param actualAction         实际选中的动作族;{@code null} 表示未产出提案
     * @param actionAcceptable     {@code actualAction} 是否落在 acceptable 集合内
     * @param payloadValidity      payload 协议有效性
     * @param executionSuccess     运行时是否真正执行成功
     * @param infra                是否为真正的网络/provider 故障
     * @param infraClass           INFRA 细分类别
     * @param protocolFailureClass 模型协议违约类别;非违约时为 {@code null}
     */
    public record DecisionObservation(String scenarioId,
                                      String actualAction,
                                      boolean actionAcceptable,
                                      PayloadValidity payloadValidity,
                                      boolean executionSuccess,
                                      boolean infra,
                                      String infraClass,
                                      String protocolFailureClass) {
    }

    /** 聚合结果;分母全部显式写出,便于报告标注样本数。 */
    public record Summary(int evaluated,
                          int infraFailures,
                          Double actionAcceptableSetAccuracy,
                          int actionMeasuredCount,
                          Double payloadValidity,
                          int payloadValidityMeasuredCount,
                          int payloadValidityNotMeasuredCount,
                          Double executionSuccessRate,
                          int modelProtocolFailures,
                          Map<String, Integer> protocolFailureBreakdown,
                          Map<String, Integer> infraClassBreakdown) {
    }

    /**
     * payload 有效性:动作产出即代表契约校验通过;未产出且属模型协议违约
     * 即判定为无效;其余(运行时从未走到 payload 校验)标记为未测量。
     */
    public static PayloadValidity payloadValidity(String actualAction, String protocolFailureClass) {
        if (actualAction != null && !actualAction.isBlank()) {
            return PayloadValidity.VALID;
        }
        if (protocolFailureClass != null) {
            return PayloadValidity.INVALID;
        }
        return PayloadValidity.NOT_MEASURED;
    }

    /**
     * 把"模型输出质量"违约与真正的 INFRA 分开:仅当失败证据指向模型/Brain
     * 输出契约(非 JSON、未知字段、payload 形状违约、未依据引用)时返回类别。
     */
    public static String classifyModelProtocolFailure(String executionResult,
                                                      Collection<String> traceErrorTypes) {
        String result = executionResult == null ? "" : executionResult.toLowerCase(Locale.ROOT);
        List<String> errors = new ArrayList<>();
        if (traceErrorTypes != null) {
            for (String error : traceErrorTypes) {
                if (error != null) {
                    errors.add(error.toLowerCase(Locale.ROOT));
                }
            }
        }
        boolean contract = result.contains("model_contract_violation")
                || result.contains("model_ungrounded_reference")
                || result.contains("agentcontractexception")
                || result.contains("modelcontractexception")
                || errors.stream().anyMatch(e -> e.contains("agentcontractexception"))
                || errors.stream().anyMatch(e -> e.contains("modelcontractexception"))
                || errors.stream().anyMatch(e -> e.contains("modelcontract"));
        if (contract) {
            return "MODEL_CONTRACT_VIOLATION";
        }
        boolean schema = result.contains("not valid json")
                || result.contains("invalidjson")
                || result.contains("extra inputs")
                || errors.stream().anyMatch(e -> e.contains("invalidjson"))
                || errors.stream().anyMatch(e -> e.contains("schema"));
        if (schema) {
            return "MODEL_OUTPUT_SCHEMA";
        }
        return null;
    }

    /** 对一批观察做 C 层聚合;INFRA 场景不计入任何质量指标。 */
    public static Summary summarize(List<DecisionObservation> observations) {
        List<DecisionObservation> evaluated = new ArrayList<>();
        int infraFailures = 0;
        Map<String, Integer> infraBreakdown = new LinkedHashMap<>();
        for (DecisionObservation observation : observations) {
            if (observation.infra()) {
                infraFailures++;
                infraBreakdown.merge(
                        observation.infraClass() == null ? "UNKNOWN" : observation.infraClass(),
                        1, Integer::sum);
            } else {
                evaluated.add(observation);
            }
        }

        List<DecisionObservation> actionMeasured = evaluated.stream()
                .filter(o -> o.actualAction() != null && !o.actualAction().isBlank())
                .toList();
        long actionCorrect = actionMeasured.stream().filter(DecisionObservation::actionAcceptable).count();

        List<DecisionObservation> payloadMeasured = evaluated.stream()
                .filter(o -> o.payloadValidity() != PayloadValidity.NOT_MEASURED)
                .toList();
        long payloadValid = payloadMeasured.stream()
                .filter(o -> o.payloadValidity() == PayloadValidity.VALID).count();

        long executed = evaluated.stream().filter(DecisionObservation::executionSuccess).count();

        Map<String, Integer> protocolBreakdown = new LinkedHashMap<>();
        for (DecisionObservation observation : evaluated) {
            if (observation.protocolFailureClass() != null) {
                protocolBreakdown.merge(observation.protocolFailureClass(), 1, Integer::sum);
            }
        }

        return new Summary(
                evaluated.size(),
                infraFailures,
                actionMeasured.isEmpty() ? null : round((double) actionCorrect / actionMeasured.size()),
                actionMeasured.size(),
                payloadMeasured.isEmpty() ? null : round((double) payloadValid / payloadMeasured.size()),
                payloadMeasured.size(),
                evaluated.size() - payloadMeasured.size(),
                evaluated.isEmpty() ? null : round((double) executed / evaluated.size()),
                protocolBreakdown.values().stream().mapToInt(Integer::intValue).sum(),
                protocolBreakdown,
                infraBreakdown);
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.4f", value));
    }
}
