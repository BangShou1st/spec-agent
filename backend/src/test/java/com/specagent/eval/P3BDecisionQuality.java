package com.specagent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件名:P3BDecisionQuality.java
 *
 * 用途:P3-B 决策质量计分的**确定性**核心:把"何时追问 / 何时调用工具 /
 * 何时结束或回应"三类判断按任务书要求的口径分开统计,每个比率都显式记录
 * 分母。纯计算,不依赖 Spring / 数据库 / 模型。
 *
 * 口径纪律(与 P3-A 的 {@code P3ADecisionScoring} 一致):
 * <ul>
 *   <li><b>真实运行故障</b>(transport / provider / 超时)单独计数,
 *       <b>绝不</b>计入任何质量比率。</li>
 *   <li><b>协议违约</b>(非 JSON / payload 形状 / 未知字段)单独计数,与
 *       网络故障、真实行为错误分开。</li>
 *   <li><b>Action acceptable-set 只在动作确实产出时判分</b>;payload 有效性
 *       只在契约校验收到了结果时判分;信息不足一律 {@code NOT_MEASURED},
 *       绝不伪造通过率。</li>
 *   <li><b>已知且被接受的确定性缺口</b>(如 {@code WAIT} 在
 *       {@code agent-input.v2} 中没有"待处理依赖"表示)单独计数,既不冒充
 *       新增缺陷,也不从分母里悄悄抹掉。</li>
 * </ul>
 *
 * 单个场景的判定由调用方(测试)在同时掌握真实运行记录与该场景 oracle 的
 * 位置完成,本类只做聚合,使每个比率的分母可审计。
 */
public final class P3BDecisionQuality {

    /** Runtime eligibility 门禁对本次提案的裁决。 */
    public enum GateOutcome {
        PASSED,
        VETOED,
        /** run 在门禁评估之前就结束(例如契约校验失败),门禁不可判。 */
        UNKNOWN
    }

    /** payload 协议有效性的三态。 */
    public enum PayloadValidity {
        VALID,
        INVALID,
        NOT_MEASURED
    }

    /** Evidence-based classification of one failed or successful attempt. */
    public enum FailureClassification {
        NONE,
        PROVIDER_UNAVAILABLE,
        RATE_LIMITED,
        TIMEOUT,
        PROVIDER_ERROR,
        NON_JSON_MODEL_OUTPUT,
        MODEL_PROTOCOL_VIOLATION,
        INVALID_ACTION_PAYLOAD,
        ELIGIBILITY_VETO,
        UNKNOWN_FAILURE;

        public boolean infrastructureFailure() {
            return this == PROVIDER_UNAVAILABLE || this == RATE_LIMITED
                    || this == TIMEOUT || this == PROVIDER_ERROR;
        }
    }

    /**
     * 单个场景尝试的 P3-B 观察。
     *
     * @param scenarioId                     场景 id
     * @param variantId                      变体 id(base / holdout)
     * @param actualAction                   被接受并记为主动作的动作族;{@code null} 表示未产出
     * @param actionAcceptable               实际动作是否落在该场景的 acceptable 集合
     * @param gateOutcome                    门禁裁决
     * @param gateReasonCodes                门禁原因码
     * @param payloadValidity                payload 协议有效性
     * @param continuationScored             该场景是否参与续跑判分
     * @param continuationCorrect            续跑裁决是否与 oracle 一致
     * @param continuationVerdict            实际续跑裁决名(不可求值时记录失败原因)
     * @param capabilityNecessityScored      该场景是否参与"工具调用必要性"判分
     * @param capabilityNecessityCorrect     必要性判断是否正确
     * @param clarificationProhibited        该场景把 REQUEST_USER_INPUT 列为禁止(分母)
     * @param unnecessaryClarification       在禁止的场景里仍然提出了追问(分子)
     * @param repeatedClarificationScored    该场景是"已答问题被重复追问"探针(分母)
     * @param repeatedClarificationMissed    门禁没能识别出这次重复追问(分子)
     * @param productionModelCalls           该 run 的真实生产模型调用数
     * @param capabilityCalls                该 run 真实发生的能力调用数
     * @param failureClassification          按类型化事件与 Trace 证据分类的结果
     */
    public record DecisionObservation(String scenarioId,
                                      String variantId,
                                      String actualAction,
                                      boolean actionAcceptable,
                                      GateOutcome gateOutcome,
                                      List<String> gateReasonCodes,
                                      PayloadValidity payloadValidity,
                                      boolean continuationScored,
                                      boolean continuationCorrect,
                                      String continuationVerdict,
                                      boolean capabilityNecessityScored,
                                      boolean capabilityNecessityCorrect,
                                      boolean clarificationProhibited,
                                      boolean unnecessaryClarification,
                                      boolean repeatedClarificationScored,
                                      boolean repeatedClarificationMissed,
                                      int productionModelCalls,
                                      int capabilityCalls,
                                      FailureClassification failureClassification) {

        public DecisionObservation {
            gateReasonCodes = gateReasonCodes == null ? List.of() : List.copyOf(gateReasonCodes);
        }

        public boolean actionMeasured() {
            return actualAction != null && !actualAction.isBlank();
        }

        public boolean infra() {
            return failureClassification.infrastructureFailure();
        }
    }

    /**
     * 聚合结果;每个比率都带显式分母。{@code null} 表示该比率在当前样本下
     * 不可测(分母为 0),绝不用 0 或 1 冒充。
     */
    public record Summary(int evaluated,
                          int infraFailures,
                          int protocolFailures,
                          Double actionAcceptableSetAccuracy,
                          int actionMeasuredCount,
                          int actionNotMeasuredCount,
                          Double unnecessaryClarificationRate,
                          int unnecessaryClarificationDenominator,
                          Double repeatedClarificationMissRate,
                          int repeatedClarificationDenominator,
                          Double capabilityNecessityAccuracy,
                          int capabilityNecessityDenominator,
                          Double stopContinueAccuracy,
                          int stopContinueDenominator,
                          Double payloadValidity,
                          int payloadValidityMeasuredCount,
                          int payloadValidityNotMeasuredCount,
                          int eligibilityFalseNegatives,
                          int eligibilityFalseNegativeDenominator,
                          int eligibilityFalsePositives,
                          int eligibilityFalsePositiveDenominator,
                          int eligibilityUnknownCount,
                          int eligibilityMeasuredCount,
                          int knownEligibilityGapsObserved,
                          int deterministicSurfaceScored,
                          int semanticOnlyNotScored,
                          Double meanModelCallsPerTask,
                          int totalModelCalls,
                          int totalCapabilityCalls,
                          int unknownFailureCount,
                          Map<String, Integer> failureClassificationBreakdown,
                          Map<String, Integer> gateVetoReasonBreakdown,
                          Map<String, Integer> actionDistribution,
                          Map<String, String> perScenarioGateVerdict,
                          Map<String, String> perScenarioContinuationVerdict,
                          Map<String, String> perScenarioClarification) {
    }

    private P3BDecisionQuality() {
    }

    /**
     * payload 有效性:动作产出即代表 Java fail-closed 契约校验通过;
     * 未产出且失败证据指向模型/Brain 输出契约即判定无效;其余(运行时从未
     * 走到 payload 校验)标记未测量。
     */
    public static PayloadValidity payloadValidity(String actualAction, String executionResult) {
        if (actualAction != null && !actualAction.isBlank()) {
            return PayloadValidity.VALID;
        }
        String result = normalize(executionResult);
        if (result.contains("model_contract_violation")
                || result.contains("model_ungrounded_reference")
                || result.contains("agentcontractexception")
                || result.contains("modelcontractexception")
                || result.contains("not valid json")
                || result.contains("extra inputs")
                || result.contains("must be a string")
                || result.contains("must be an object")) {
            return PayloadValidity.INVALID;
        }
        return PayloadValidity.NOT_MEASURED;
    }

    /**
     * 只有真正的 transport / provider / 超时故障才算 INFRA。门禁否决导致的
     * 失败是<b>决策结果</b>,能力调用失败是<b>行为结果</b>,二者都不是网络故障。
     */
    public static FailureClassification classifyFailure(String executionResult,
                                                        String modelInferenceFailureCategory,
                                                        Map<String, Object> eligibilityStage,
                                                        String failureErrorType,
                                                        String failureErrorSummary) {
        FailureClassification provider = providerFailure(modelInferenceFailureCategory);
        if (provider != null) {
            return provider;
        }

        String reason = failureReason(executionResult);
        if ("brain_timeout".equals(reason) || "brokertimeouterror".equalsIgnoreCase(failureErrorType)) {
            return FailureClassification.TIMEOUT;
        }
        if ("brain_unavailable".equals(reason) || "model_provider_failure".equals(reason)) {
            return FailureClassification.PROVIDER_UNAVAILABLE;
        }
        if ("actionineligibleexception".equalsIgnoreCase(reason)
                || "actionineligibleexception".equalsIgnoreCase(failureErrorType)
                || enforcedEligibilityVeto(eligibilityStage)) {
            return FailureClassification.ELIGIBILITY_VETO;
        }

        if ("JsonDecodeError".equals(failureErrorType)
                || "JSONDecodeError".equals(failureErrorType)
                || "NonJsonBrainOutputError".equals(failureErrorType)
                || "model output is not valid JSON".equals(failureErrorSummary)) {
            return FailureClassification.NON_JSON_MODEL_OUTPUT;
        }
        if ("InvalidActionPayloadException".equals(failureErrorType)
                || "ActionPayloadException".equals(failureErrorType)) {
            return FailureClassification.INVALID_ACTION_PAYLOAD;
        }
        if ("BrainContractError".equals(failureErrorType)
                || "AgentContractException".equals(failureErrorType)
                || "ModelContractException".equals(failureErrorType)
                || "model_contract_violation".equals(reason)
                || "model_ungrounded_reference".equals(reason)) {
            return FailureClassification.MODEL_PROTOCOL_VIOLATION;
        }

        if (executionResult == null || executionResult.isBlank()
                || executionResult.startsWith("failed:")) {
            return FailureClassification.UNKNOWN_FAILURE;
        }
        return switch (executionResult) {
            case "completed", "policy_denied" -> FailureClassification.NONE;
            default -> executionResult.startsWith("awaiting_approval:")
                    ? FailureClassification.NONE : FailureClassification.UNKNOWN_FAILURE;
        };
    }

    private static FailureClassification providerFailure(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return switch (category) {
            case "RATE_LIMITED" -> FailureClassification.RATE_LIMITED;
            case "TIMEOUT" -> FailureClassification.TIMEOUT;
            case "CONNECTION", "SERVER_ERROR" -> FailureClassification.PROVIDER_UNAVAILABLE;
            case "AUTHENTICATION", "INVALID_MODEL", "PROVIDER_REQUEST_ERROR",
                 "INVALID_RESPONSE", "EMPTY_CONTENT", "NOT_CONFIGURED" ->
                    FailureClassification.PROVIDER_ERROR;
            default -> FailureClassification.UNKNOWN_FAILURE;
        };
    }

    private static String failureReason(String executionResult) {
        if (executionResult == null || !executionResult.startsWith("failed:")) {
            return "";
        }
        String detail = executionResult.substring("failed:".length());
        int separator = detail.indexOf(':');
        return (separator < 0 ? detail : detail.substring(0, separator))
                .toLowerCase(Locale.ROOT);
    }

    private static boolean enforcedEligibilityVeto(Map<String, Object> eligibilityStage) {
        if (eligibilityStage == null
                || !"VETO".equals(eligibilityStage.get("post_selection_veto_result"))) {
            return false;
        }
        Object mapping = eligibilityStage.get("action_ineligible_mapping");
        return mapping instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("enforced"));
    }

    /** 从语义追踪的 {@code ACTION_ELIGIBILITY} 阶段读取门禁裁决。 */
    public static GateOutcome gateOutcome(Map<String, Object> eligibilityStage) {
        if (eligibilityStage == null) {
            return GateOutcome.UNKNOWN;
        }
        Object vetoResult = eligibilityStage.get("post_selection_veto_result");
        if (vetoResult == null) {
            return GateOutcome.UNKNOWN;
        }
        return switch (String.valueOf(vetoResult)) {
            case "PASS" -> GateOutcome.PASSED;
            case "VETO" -> GateOutcome.VETOED;
            default -> GateOutcome.UNKNOWN;
        };
    }

    /** 从语义追踪的 {@code ACTION_ELIGIBILITY} 阶段读取门禁原因码。 */
    public static List<String> gateReasonCodes(Map<String, Object> eligibilityStage) {
        if (eligibilityStage == null) {
            return List.of();
        }
        Object codes = eligibilityStage.get("post_selection_veto_reason_codes");
        if (codes instanceof List<?> list) {
            List<String> rendered = new ArrayList<>();
            for (Object code : list) {
                if (code != null) {
                    rendered.add(String.valueOf(code));
                }
            }
            return rendered;
        }
        return List.of();
    }

    public static Summary summarize(List<DecisionObservation> observations,
                                    Map<String, P3BCorpus.P3BOracle> oracles) {
        List<DecisionObservation> evaluated = new ArrayList<>();
        int infraFailures = 0;
        Map<String, Integer> failureClassificationBreakdown = new LinkedHashMap<>();
        for (DecisionObservation observation : observations) {
            failureClassificationBreakdown.merge(
                    observation.failureClassification().name(), 1, Integer::sum);
            if (observation.infra()) {
                infraFailures++;
            } else {
                evaluated.add(observation);
            }
        }

        List<DecisionObservation> actionMeasured =
                evaluated.stream().filter(DecisionObservation::actionMeasured).toList();
        long actionCorrect = actionMeasured.stream()
                .filter(DecisionObservation::actionAcceptable).count();

        long unnecessaryClarification = evaluated.stream()
                .filter(DecisionObservation::unnecessaryClarification).count();
        int clarificationDenominator = (int) evaluated.stream()
                .filter(DecisionObservation::clarificationProhibited).count();

        long repeatedMissed = evaluated.stream()
                .filter(DecisionObservation::repeatedClarificationMissed).count();
        int repeatedDenominator = (int) evaluated.stream()
                .filter(DecisionObservation::repeatedClarificationScored).count();

        List<DecisionObservation> capabilityScored = evaluated.stream()
                .filter(DecisionObservation::capabilityNecessityScored).toList();
        long capabilityCorrect = capabilityScored.stream()
                .filter(DecisionObservation::capabilityNecessityCorrect).count();

        List<DecisionObservation> continuationScored = evaluated.stream()
                .filter(DecisionObservation::continuationScored).toList();
        long continuationCorrect = continuationScored.stream()
                .filter(DecisionObservation::continuationCorrect).count();

        List<DecisionObservation> payloadMeasured = evaluated.stream()
                .filter(observation -> observation.payloadValidity()
                        != PayloadValidity.NOT_MEASURED)
                .toList();
        long payloadValid = payloadMeasured.stream()
                .filter(observation -> observation.payloadValidity() == PayloadValidity.VALID)
                .count();

        List<DecisionObservation> passExpected = evaluated.stream()
                .filter(observation -> expectation(oracles, observation)
                        == P3BCorpus.GateExpectation.PASS)
                .toList();
        long eligibilityFalseNegatives = passExpected.stream()
                .filter(observation -> observation.gateOutcome() == GateOutcome.VETOED)
                .count();

        List<DecisionObservation> vetoExpected = evaluated.stream()
                .filter(observation -> expectation(oracles, observation)
                        == P3BCorpus.GateExpectation.VETO)
                .toList();
        long eligibilityFalsePositives = vetoExpected.stream()
                .filter(observation -> !knownGap(oracles, observation))
                .filter(observation -> observation.gateOutcome() == GateOutcome.PASSED)
                .count();
        long knownGapsObserved = vetoExpected.stream()
                .filter(observation -> knownGap(oracles, observation))
                .count();
        int eligibilityUnknowns = (int) evaluated.stream()
                .filter(observation -> observation.gateOutcome() == GateOutcome.UNKNOWN).count();

        int deterministicSurface = (int) evaluated.stream()
                .filter(observation -> expectation(oracles, observation)
                        != P3BCorpus.GateExpectation.NO_DETERMINISTIC_SURFACE)
                .count();

        Map<String, Integer> vetoReasons = new LinkedHashMap<>();
        Map<String, Integer> actions = new LinkedHashMap<>();
        Map<String, String> perScenario = new LinkedHashMap<>();
        Map<String, String> perScenarioContinuation = new LinkedHashMap<>();
        Map<String, String> perScenarioClarification = new LinkedHashMap<>();
        int totalCalls = 0;
        int totalCapabilityCalls = 0;
        for (DecisionObservation observation : evaluated) {
            observation.gateReasonCodes()
                    .forEach(code -> vetoReasons.merge(code, 1, Integer::sum));
            actions.merge(observation.actualAction() == null ? "<none>" : observation.actualAction(),
                    1, Integer::sum);
            totalCalls += observation.productionModelCalls();
            totalCapabilityCalls += observation.capabilityCalls();
            P3BCorpus.GateExpectation expected = expectation(oracles, observation);
            String verdict;
            if (expected == P3BCorpus.GateExpectation.NO_DETERMINISTIC_SURFACE) {
                verdict = "SEMANTIC_NOT_SCORED";
            } else if (observation.gateOutcome() == expectedOutcome(expected)) {
                verdict = "GATE_OK";
            } else {
                verdict = "GATE_MISMATCH";
            }
            perScenario.put(observation.scenarioId() + "/" + observation.variantId(), verdict);
            if (observation.continuationScored()) {
                perScenarioContinuation.put(
                        observation.scenarioId() + "/" + observation.variantId(),
                        observation.continuationVerdict()
                                + (observation.continuationCorrect() ? " (ok)" : " (MISMATCH)"));
            }
            if (observation.clarificationProhibited()) {
                perScenarioClarification.put(
                        observation.scenarioId() + "/" + observation.variantId(),
                        "clarification-forbidden, actual=" + observation.actualAction()
                                + (observation.unnecessaryClarification() ? " [UNNECESSARY]" : ""));
            }
        }

        int protocolFailures = (int) evaluated.stream()
                .filter(observation -> observation.failureClassification()
                        == FailureClassification.NON_JSON_MODEL_OUTPUT
                        || observation.failureClassification()
                        == FailureClassification.MODEL_PROTOCOL_VIOLATION)
                .count();
        int unknownFailures = (int) observations.stream()
                .filter(observation -> observation.failureClassification()
                        == FailureClassification.UNKNOWN_FAILURE).count();

        return new Summary(
                evaluated.size(),
                infraFailures,
                protocolFailures,
                ratio(actionCorrect, actionMeasured.size()),
                actionMeasured.size(),
                evaluated.size() - actionMeasured.size(),
                ratio(unnecessaryClarification, clarificationDenominator),
                clarificationDenominator,
                ratio(repeatedMissed, repeatedDenominator),
                repeatedDenominator,
                ratio(capabilityCorrect, capabilityScored.size()),
                capabilityScored.size(),
                ratio(continuationCorrect, continuationScored.size()),
                continuationScored.size(),
                ratio(payloadValid, payloadMeasured.size()),
                payloadMeasured.size(),
                evaluated.size() - payloadMeasured.size(),
                (int) eligibilityFalseNegatives,
                passExpected.size(),
                (int) eligibilityFalsePositives,
                vetoExpected.size() - (int) knownGapsObserved,
                eligibilityUnknowns,
                evaluated.size() - eligibilityUnknowns,
                (int) knownGapsObserved,
                deterministicSurface,
                evaluated.size() - deterministicSurface,
                evaluated.isEmpty() ? null : round((double) totalCalls / evaluated.size()),
                totalCalls,
                totalCapabilityCalls,
                unknownFailures,
                failureClassificationBreakdown,
                vetoReasons,
                actions,
                perScenario,
                perScenarioContinuation,
                perScenarioClarification);
    }

    private static GateOutcome expectedOutcome(P3BCorpus.GateExpectation expectation) {
        return expectation == P3BCorpus.GateExpectation.VETO
                ? GateOutcome.VETOED : GateOutcome.PASSED;
    }

    private static P3BCorpus.GateExpectation expectation(Map<String, P3BCorpus.P3BOracle> oracles,
                                                         DecisionObservation observation) {
        return oracle(oracles, observation).gate();
    }

    private static boolean knownGap(Map<String, P3BCorpus.P3BOracle> oracles,
                                    DecisionObservation observation) {
        return oracle(oracles, observation).knownEligibilityGap();
    }

    private static P3BCorpus.P3BOracle oracle(Map<String, P3BCorpus.P3BOracle> oracles,
                                              DecisionObservation observation) {
        P3BCorpus.P3BOracle oracle = oracles.get(observation.scenarioId());
        if (oracle == null) {
            throw new IllegalArgumentException(
                    "Missing P3-B oracle for scenario " + observation.scenarioId());
        }
        return oracle;
    }

    private static Double ratio(long numerator, int denominator) {
        return denominator == 0 ? null : round((double) numerator / denominator);
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.4f", value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
