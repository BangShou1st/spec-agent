package com.specagent.eval;

import com.specagent.agent.runtime.ContinuationCoordinator;
import com.specagent.agent.runtime.ContinuationDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:P3BDecisionQualityTest.java
 *
 * 测试目标:P3-B 决策质量基线(确定性,离线,B-fast 档)。
 *
 * 把 {@link P3BCorpus} 的 18 个类别场景跑过**真实 Java 运行时**
 * (ScriptedBrain 只在结构化大脑输出边界替身),读取每次尝试的:
 * 语义追踪里的 {@code ACTION_ELIGIBILITY} 裁决、真实续跑裁决
 * ({@link ContinuationCoordinator})、主动作、payload 协议结果与真实调用记账,
 * 再按 {@link P3BDecisionQuality} 的口径聚合成带显式分母的产物。
 *
 * 诚实性边界:provider 是脚本化大脑,因此这里得到的是 **Runtime 门禁质量**
 * 的 deterministic 基线,**不是**真实模型决策质量;真实模型结果由独立 live suite 记录。
 * 产物写入 {@code build/eval-p3b}(绝不提交)。
 */
class P3BDecisionQualityTest extends EvalHarnessBase {

    @Autowired
    private ContinuationCoordinator continuationCoordinator;

    @Test
    void unknownGateValuesRemainUnknown() {
        assertThat(P3BDecisionQuality.gateOutcome(Map.of(
                "post_selection_veto_result", "MAYBE")))
                .isEqualTo(P3BDecisionQuality.GateOutcome.UNKNOWN);
        assertThat(P3BDecisionQuality.gateOutcome(Map.of(
                "post_selection_veto_result", "PASS")))
                .isEqualTo(P3BDecisionQuality.GateOutcome.PASSED);
        assertThat(P3BDecisionQuality.gateOutcome(Map.of(
                "post_selection_veto_result", "VETO")))
                .isEqualTo(P3BDecisionQuality.GateOutcome.VETOED);
    }

    @Test
    void failureClassifierUsesTypedCategoriesAndLeavesWeakEvidenceUnknown() {
        assertThat(classifyFailure("failed:provider", "RATE_LIMITED", null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.RATE_LIMITED);
        assertThat(classifyFailure("failed:provider", "TIMEOUT", null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.TIMEOUT);
        assertThat(classifyFailure("failed:provider", "CONNECTION", null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.PROVIDER_UNAVAILABLE);
        assertThat(classifyFailure("failed:provider", "AUTHENTICATION", null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.PROVIDER_ERROR);
        assertThat(classifyFailure("failed:bad-json", null, null,
                "JsonDecodeError", "decode failed"))
                .isEqualTo(P3BDecisionQuality.FailureClassification.NON_JSON_MODEL_OUTPUT);
        assertThat(classifyFailure("failed:contract", null, null,
                "BrainContractError", "schema mismatch"))
                .isEqualTo(P3BDecisionQuality.FailureClassification.MODEL_PROTOCOL_VIOLATION);
        assertThat(classifyFailure("failed:payload", null, null,
                "InvalidActionPayloadException", "invalid payload"))
                .isEqualTo(P3BDecisionQuality.FailureClassification.INVALID_ACTION_PAYLOAD);
        assertThat(classifyFailure("failed:ActionIneligibleException", null, null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.ELIGIBILITY_VETO);
        assertThat(classifyFailure(
                "failed:AgentBrainUnavailableException: provider unavailable", null, null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.UNKNOWN_FAILURE);
    }

    private P3BDecisionQuality.FailureClassification classifyFailure(
            String result, String providerCategory, Map<String, Object> eligibilityStage,
            String errorType, String errorSummary) {
        return P3BDecisionQuality.classifyFailure(
                result, providerCategory, eligibilityStage, errorType, errorSummary);
    }

    @Test
    void runP3bCorpusAndWriteDecisionQualityArtifacts() throws Exception {
        List<P3BCorpus.P3BScenario> corpus = P3BCorpus.all();
        Map<String, P3BCorpus.P3BOracle> oracles = new LinkedHashMap<>();
        for (P3BCorpus.P3BScenario scenario : corpus) {
            oracles.put(scenario.scenarioId(), scenario.oracle());
        }

        List<ObservationEnvelope> observations = new ArrayList<>();
        List<P3BDecisionQuality.DecisionObservation> decisionObservations = new ArrayList<>();
        for (P3BCorpus.P3BScenario scenario : corpus) {
            for (VariantSpec variant : scenario.definition().variants()) {
                ObservationEnvelope observation = runScenario(scenario.definition(), variant);
                observations.add(observation);
                decisionObservations.add(decide(scenario, variant, observation,
                        scenarioRunner.lastProjectId()));
            }
        }

        P3BDecisionQuality.Summary summary =
                P3BDecisionQuality.summarize(decisionObservations, oracles);

        Path outputDir = Path.of(System.getProperty("user.dir"), "build", "eval-p3b");
        Files.createDirectories(outputDir);
        StringBuilder jsonl = new StringBuilder();
        for (ObservationEnvelope observation : observations) {
            jsonl.append(EvalArtifactWriter.toJsonl(observation)).append("\n");
        }
        Files.writeString(outputDir.resolve("p3b-observations.jsonl"), jsonl.toString(),
                StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("p3b-decision-quality.json"),
                EvalArtifactWriter.toJson(renderSummary(summary)), StandardCharsets.UTF_8);

        System.out.println("P3-B decision quality baseline: " + outputDir.toAbsolutePath());
        System.out.println(renderText(summary));

        // 结构性完整:每个场景的每个变体都必须真实终结,且分母与语料声明一致。
        assertThat(summary.evaluated() + summary.infraFailures())
                .as("every declared variant must produce one observation")
                .isEqualTo(countAttempts(corpus));
        assertThat(summary.actionMeasuredCount())
                .as("shadow mode does not enforce, so every attempt yields a primary action")
                .isEqualTo(summary.evaluated());
        assertThat(summary.meanModelCallsPerTask())
                .as("one answer cycle is exactly STATE_UPDATE + DECISION")
                .isEqualTo(2.0);

        // -- 基线冻结(确定性档案,prefer 精确不变式 / 单向界) -----------------
        //
        // 精确不变式:这些是**修复后必须成立**的确定性事实。任何一个不成立
        // 都意味着修复被回退或被错误地放宽。
        assertThat(summary.eligibilityFalsePositives())
                .as("P3-B 基线曾测得 4/8 的 eligibility false positive:"
                        + "被调用能力声明的必填参数缺失,却仍在 eligibility 边界被放行;"
                        + "该缺口已由 MISSING_REQUIRED_CAPABILITY_ARGUMENT 关闭")
                .isZero();
        assertThat(summary.eligibilityFalsePositiveDenominator())
                .as("期望 veto 的场景数(C06/C09/C02/C14/C16 × 2 变体)")
                .isEqualTo(8);
        assertThat(summary.eligibilityUnknownCount())
                .as("unknown gate outcomes remain visible and fail this complete-observation gate")
                .isZero();
        assertThat(summary.eligibilityMeasuredCount()).isEqualTo(summary.evaluated());
        assertThat(summary.eligibilityFalseNegatives())
                .as("修复不得制造新的 false negative:参数齐全的调用(C08/C17)"
                        + "与未声明必填字段的能力都必须保持 eligible")
                .isZero();
        assertThat(summary.eligibilityFalseNegativeDenominator())
                .as("期望放行的场景数(9 个 × 2 变体)")
                .isEqualTo(18);
        assertThat(summary.gateVetoReasonBreakdown())
                .containsEntry("MISSING_REQUIRED_CAPABILITY_ARGUMENT", 4)
                .containsEntry("RESOLVED_BLOCKER", 2)
                .containsEntry("ANSWER_ALREADY_DURABLE", 2)
                .as("WAIT 的 NO_PENDING_DEPENDENCY 是已知且被接受的缺口,单独计数")
                .containsEntry("NO_PENDING_DEPENDENCY", 2);
        assertThat(summary.knownEligibilityGapsObserved())
                .as("C16 的 WAIT 缺口(base + holdout),既不算新增缺陷,也不从分母抹掉")
                .isEqualTo(2);
        assertThat(summary.repeatedClarificationMissRate())
                .as("逐字重复的已答问题必须被门禁识别")
                .isEqualTo(0.0);
        assertThat(summary.payloadValidity())
                .as("所有尝试都通过了 Java fail-closed 契约校验")
                .isEqualTo(1.0);
        assertThat(summary.payloadValidityMeasuredCount()).isEqualTo(summary.evaluated());
        assertThat(summary.totalModelCalls())
                .as("本轮优化绝不增加每个用户任务的模型调用次数")
                .isEqualTo(2 * summary.evaluated());

        // 单向界:以下比率反映**尚未修复的语义缺口**(不是门禁缺陷),
        // 因此只设上/下界——允许将来变好,不允许变坏。
        assertThat(summary.unnecessaryClarificationDenominator())
                .as("把追问列为禁止的场景数,分母必须显式且可审计")
                .isEqualTo(14);
        assertThat(summary.unnecessaryClarificationRate())
                .as("4/14:逐字重复(门禁已判定但 shadow 不执行)与改写重复(门禁无判据)")
                .isLessThanOrEqualTo(0.2857);
        assertThat(summary.capabilityNecessityDenominator()).isEqualTo(16);
        assertThat(summary.capabilityNecessityAccuracy())
                .as("6/16 未命中 = 4 个已被门禁判定的缺参调用(见上)"
                        + " + 2 个参数齐全但当前并不需要调用的语义缺口(C07)")
                .isGreaterThanOrEqualTo(0.625);
        assertThat(summary.semanticOnlyNotScored())
                .as("语义类判断在 Runtime 门禁层没有确定性判据,必须如实标注而不是假装可判")
                .isEqualTo(8);
        assertThat(summary.totalCapabilityCalls())
                .as("shadow 档位刻意不改变生产行为:修复只改变门禁裁决")
                .isLessThanOrEqualTo(10);
    }

    private P3BDecisionQuality.DecisionObservation decide(P3BCorpus.P3BScenario scenario,
                                                          VariantSpec variant,
                                                          ObservationEnvelope observation,
                                                          UUID projectId) {
        P3BCorpus.P3BOracle oracle = scenario.oracle();
        Map<String, Object> eligibilityStage =
                observation.semanticTrace().stages().get("ACTION_ELIGIBILITY");
        String actualAction = observation.actualPrimaryAction();
        String executionResult = observation.executionResult();
        P3BDecisionQuality.FailureClassification failureClassification =
                P3BDecisionQuality.classifyFailure(
                        executionResult,
                        modelInferenceFailureCategory(observation.attemptId()),
                        eligibilityStage,
                        traceError(observation, "error_type"),
                        traceError(observation, "error_summary"));
        boolean infra = failureClassification.infrastructureFailure();

        boolean forbiddenClarification =
                scenario.definition().expect().forbiddenActions().contains("REQUEST_USER_INPUT");
        boolean unnecessary = forbiddenClarification
                && "REQUEST_USER_INPUT".equals(actualAction);

        boolean repeatedScored = oracle.vetoReasonCodes().contains("RESOLVED_BLOCKER");
        boolean repeatedMissed = repeatedScored
                && P3BDecisionQuality.gateOutcome(eligibilityStage)
                != P3BDecisionQuality.GateOutcome.VETOED;

        boolean capabilityNecessityCorrect = capabilityNecessityCorrect(scenario, infra, actualAction);

        String expectedVerdict = oracle.continuationVerdict();
        String actualVerdict = actualContinuationVerdict(observation);
        boolean continuationScored = expectedVerdict != null && observation.attemptId() != null;
        boolean continuationCorrect = continuationScored && continuationHolds(expectedVerdict, actualVerdict);

        String selectedAction = modelSelectedAction(observation);
        boolean selectedAcceptable = selectedAction != null
                && scenario.definition().expect().acceptablePrimaryActions().contains(selectedAction);

        return new P3BDecisionQuality.DecisionObservation(
                scenario.scenarioId(), variant.variantId(),
                selectedAction, selectedAcceptable,
                actualAction,
                P3BDecisionQuality.executedAction(actualAction, executionResult),
                executionResult,
                actualAction != null
                        && scenario.definition().expect().acceptablePrimaryActions()
                        .contains(actualAction),
                P3BDecisionQuality.gateOutcome(eligibilityStage),
                P3BDecisionQuality.gateReasonCodes(eligibilityStage),
                P3BDecisionQuality.payloadValidity(actualAction, executionResult),
                continuationScored, continuationCorrect, actualVerdict,
                oracle.capabilityNecessityScored()
                        && (failureClassification == P3BDecisionQuality.FailureClassification.NONE
                        || failureClassification == P3BDecisionQuality.FailureClassification.ELIGIBILITY_VETO),
                capabilityNecessityCorrect,
                forbiddenClarification, unnecessary,
                repeatedScored, repeatedMissed,
                observation.productionModelCalls(), persistedCapabilityInvocations(projectId),
                failureClassification);
    }

    /**
     * 模型**原始**选择的动作族:优先取 DECISION_OUTPUT 的 normalized_output.actionFamily,
     * 回退到 ACTION_ELIGIBILITY.selected_action。二者都是"模型真正选了什么"的运行时证据,
     * 与被 Java 门禁接受的动作分开记录。
     */
    private static String modelSelectedAction(ObservationEnvelope observation) {
        Map<String, Object> decision = observation.semanticTrace().stages().get("DECISION_OUTPUT");
        if (decision != null) {
            Object normalized = decision.get("normalized_output");
            if (normalized instanceof Map<?, ?> proposal && proposal.get("actionFamily") != null) {
                return String.valueOf(proposal.get("actionFamily"));
            }
        }
        Map<String, Object> eligibility = observation.semanticTrace().stages().get("ACTION_ELIGIBILITY");
        if (eligibility != null && eligibility.get("selected_action") != null) {
            return String.valueOf(eligibility.get("selected_action"));
        }
        return null;
    }

    private String modelInferenceFailureCategory(String runId) {
        if (runId == null) {
            return null;
        }
        return jdbcTemplate.query(
                "SELECT payload ->> 'gatewayCategory' FROM agent_run_events "
                        + "WHERE run_id = ? AND event_type = 'MODEL_INFERENCE_FAILED' "
                        + "ORDER BY sequence DESC LIMIT 1",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                UUID.fromString(runId));
    }

    private static String traceError(ObservationEnvelope observation, String field) {
        for (String stageName : List.of("DECISION_OUTPUT", "STATE_UPDATE_OUTPUT")) {
            Map<String, Object> stage = observation.semanticTrace().stages().get(stageName);
            if (stage != null && stage.get(field) != null) {
                return String.valueOf(stage.get(field));
            }
        }
        return null;
    }

    /**
     * 续跑裁决的判据。
     *
     * {@code EXECUTED_NEW_OBSERVATION} 表示"本轮留下了可被下一个快照消费的
     * 新事实,链应当继续"。生产回答周期在运行时已经执行过这一步续跑,因此
     * 事后重放同一个 {@link ContinuationCoordinator#evaluate} 必然先命中
     * {@code ALREADY_CONTINUED}——"该边界已经被续跑过一次,再造就分叉"。
     * 两者语义等价,后者甚至更强(它证明续跑子 run 确实已落库,而不是仅仅
     * 判定为可续跑)。这里只有这一处等价,不做其他放宽。
     */
    private boolean continuationHolds(String expectedVerdict, String actualVerdict) {
        if (expectedVerdict.equals(actualVerdict)) {
            return true;
        }
        return "EXECUTED_NEW_OBSERVATION".equals(expectedVerdict)
                && "ALREADY_CONTINUED".equals(actualVerdict);
    }

    /**
     * 该 run 真实持久化的能力调用行数(含成功与失败——失败同样作为证据落库)。
     * 这与探针计数不同:非探针的真实能力(memory.search)也会被算进来,
     * 因此"调用必填参数缺失"造成的无效调用不再被评测框架漏掉。
     */
    private int persistedCapabilityInvocations(UUID projectId) {
        if (projectId == null) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capability_invocations WHERE project_id = ?",
                Integer.class, projectId);
        return count == null ? 0 : count;
    }

    /**
     * "工具调用必要性"判定是否正确:该场景把 INVOKE_CAPABILITY 列为禁止时,
     * 只要最终没有调用就算对;列为唯一可接受动作时,必须真的调用。
     */
    private boolean capabilityNecessityCorrect(P3BCorpus.P3BScenario scenario,
                                               boolean infra,
                                               String actualAction) {
        if (infra) {
            return false;
        }
        boolean invokeForbidden = scenario.definition().expect().forbiddenActions()
                .contains("INVOKE_CAPABILITY");
        if (invokeForbidden) {
            return !"INVOKE_CAPABILITY".equals(actualAction);
        }
        boolean invokeAcceptable = scenario.definition().expect().acceptablePrimaryActions()
                .contains("INVOKE_CAPABILITY");
        return !invokeAcceptable || "INVOKE_CAPABILITY".equals(actualAction);
    }

    private String actualContinuationVerdict(ObservationEnvelope observation) {
        try {
            ContinuationDecision decision =
                    continuationCoordinator.evaluate(UUID.fromString(observation.attemptId()));
            return decision.verdict().name();
        } catch (RuntimeException ex) {
            return "EVALUATION_FAILED:" + ex.getClass().getSimpleName();
        }
    }

    private static int countAttempts(List<P3BCorpus.P3BScenario> corpus) {
        int attempts = 0;
        for (P3BCorpus.P3BScenario scenario : corpus) {
            attempts += scenario.definition().variants().size();
        }
        return attempts;
    }

    private static Map<String, Object> renderSummary(P3BDecisionQuality.Summary summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema_version", "p3b-decision-quality.v2");
        map.put("schema_migration",
                "v1 execution_success_rate 把 Run 终态(policy_denied/awaiting_approval)与动作执行混为一谈;"
                        + "v2 拆分为 run_normal_terminal_rate / action_submitted_rate / action_completed_rate,"
                        + "并保留 execution_success_rate 作为 action_completed_rate 的兼容别名(v1 的分母等于 action_completed_measured)。");
        map.put("corpus", "P3BCorpus");
        map.put("provider", "fake");
        map.put("model", "scripted-brain");
        map.put("evaluation_profile", "B_FAST");
        map.put("honesty", "deterministic scripted-brain baseline of Runtime gating quality; "
                + "NOT real-model decision quality (see separate live P3-B report)");
        map.put("label_review", "PENDING_HUMAN_REVIEW");
        map.put("evaluated", summary.evaluated());
        map.put("action_acceptable_set_accuracy", summary.actionAcceptableSetAccuracy());
        map.put("action_measured", summary.actionMeasuredCount());
        map.put("action_not_measured", summary.actionNotMeasuredCount());
        map.put("model_selection_accuracy", summary.modelSelectionAccuracy());
        map.put("model_selection_measured", summary.modelSelectionMeasuredCount());
        map.put("model_selection_not_measured", summary.modelSelectionNotMeasuredCount());
        map.put("gate_accuracy", summary.gateAccuracy());
        map.put("gate_measured", summary.gateMeasuredCount());
        map.put("run_normal_terminal_rate", summary.runNormalTerminalRate());
        map.put("run_normal_terminal_measured", summary.runNormalTerminalMeasuredCount());
        map.put("action_submitted_rate", summary.actionSubmittedRate());
        map.put("action_submitted_measured", summary.actionSubmittedMeasuredCount());
        map.put("action_completed_rate", summary.actionCompletedRate());
        map.put("action_completed_measured", summary.actionCompletedMeasuredCount());
        // v1 兼容别名(见 schema_migration):v1 的 execution_success_rate 实际只等于
        // "动作成功完成率",其分母等于 action_completed_measured。
        map.put("execution_success_rate", summary.actionCompletedRate());
        map.put("execution_measured", summary.actionCompletedMeasuredCount());
        map.put("unnecessary_clarification_rate", summary.unnecessaryClarificationRate());
        map.put("unnecessary_clarification_denominator",
                summary.unnecessaryClarificationDenominator());
        map.put("repeated_clarification_miss_rate", summary.repeatedClarificationMissRate());
        map.put("repeated_clarification_denominator", summary.repeatedClarificationDenominator());
        map.put("capability_necessity_accuracy", summary.capabilityNecessityAccuracy());
        map.put("capability_necessity_denominator", summary.capabilityNecessityDenominator());
        map.put("stop_continue_accuracy", summary.stopContinueAccuracy());
        map.put("stop_continue_denominator", summary.stopContinueDenominator());
        map.put("payload_validity", summary.payloadValidity());
        map.put("payload_validity_measured", summary.payloadValidityMeasuredCount());
        map.put("payload_validity_not_measured", summary.payloadValidityNotMeasuredCount());
        map.put("eligibility_false_negatives", summary.eligibilityFalseNegatives());
        map.put("eligibility_false_negative_denominator",
                summary.eligibilityFalseNegativeDenominator());
        map.put("eligibility_false_positives", summary.eligibilityFalsePositives());
        map.put("eligibility_false_positive_denominator",
                summary.eligibilityFalsePositiveDenominator());
        map.put("eligibility_unknown_count", summary.eligibilityUnknownCount());
        map.put("eligibility_measured_count", summary.eligibilityMeasuredCount());
        map.put("known_eligibility_gaps_observed", summary.knownEligibilityGapsObserved());
        map.put("deterministic_surface_scored", summary.deterministicSurfaceScored());
        map.put("semantic_only_not_scored", summary.semanticOnlyNotScored());
        map.put("mean_model_calls_per_task", summary.meanModelCallsPerTask());
        map.put("total_model_calls", summary.totalModelCalls());
        map.put("total_capability_calls", summary.totalCapabilityCalls());
        map.put("protocol_failures", summary.protocolFailures());
        map.put("infra_failures", summary.infraFailures());
        map.put("unknown_failures", summary.unknownFailureCount());
        map.put("failure_classification_breakdown", summary.failureClassificationBreakdown());
        map.put("gate_veto_reason_breakdown", summary.gateVetoReasonBreakdown());
        map.put("action_distribution", summary.actionDistribution());
        map.put("per_scenario_gate_verdict", summary.perScenarioGateVerdict());
        map.put("per_scenario_continuation_verdict", summary.perScenarioContinuationVerdict());
        map.put("per_scenario_clarification", summary.perScenarioClarification());
        return map;
    }

    private static String renderText(P3BDecisionQuality.Summary summary) {
        StringBuilder text = new StringBuilder();
        text.append("evaluated=").append(summary.evaluated())
                .append(" infra=").append(summary.infraFailures())
                .append(" protocolFailures=").append(summary.protocolFailures()).append('\n');
        text.append("actionAcceptableSetAccuracy=").append(summary.actionAcceptableSetAccuracy())
                .append(" (n=").append(summary.actionMeasuredCount()).append(")\n");
        text.append("unnecessaryClarificationRate=").append(summary.unnecessaryClarificationRate())
                .append(" (n=").append(summary.unnecessaryClarificationDenominator()).append(")\n");
        text.append("repeatedClarificationMissRate=").append(summary.repeatedClarificationMissRate())
                .append(" (n=").append(summary.repeatedClarificationDenominator()).append(")\n");
        text.append("capabilityNecessityAccuracy=").append(summary.capabilityNecessityAccuracy())
                .append(" (n=").append(summary.capabilityNecessityDenominator()).append(")\n");
        text.append("stopContinueAccuracy=").append(summary.stopContinueAccuracy())
                .append(" (n=").append(summary.stopContinueDenominator()).append(")\n");
        text.append("payloadValidity=").append(summary.payloadValidity())
                .append(" (n=").append(summary.payloadValidityMeasuredCount()).append(")\n");
        text.append("eligibilityFalseNegatives=").append(summary.eligibilityFalseNegatives())
                .append(" (n=").append(summary.eligibilityFalseNegativeDenominator()).append(")\n");
        text.append("eligibilityFalsePositives=").append(summary.eligibilityFalsePositives())
                .append(" (n=").append(summary.eligibilityFalsePositiveDenominator()).append(")\n");
        text.append("eligibilityMeasured=").append(summary.eligibilityMeasuredCount())
                .append(" eligibilityUnknown=").append(summary.eligibilityUnknownCount()).append('\n');
        text.append("failureClassifications=").append(summary.failureClassificationBreakdown())
                .append(" unknownFailures=").append(summary.unknownFailureCount()).append('\n');
        text.append("knownEligibilityGapsObserved=").append(summary.knownEligibilityGapsObserved())
                .append('\n');
        text.append("meanModelCallsPerTask=").append(summary.meanModelCallsPerTask())
                .append(" totalModelCalls=").append(summary.totalModelCalls())
                .append(" capabilityCalls=").append(summary.totalCapabilityCalls()).append('\n');
        text.append("gateVetoReasons=").append(summary.gateVetoReasonBreakdown()).append('\n');
        text.append("actions=").append(summary.actionDistribution()).append('\n');
        text.append("perScenario=").append(summary.perScenarioGateVerdict()).append('\n');
        text.append("continuation=").append(summary.perScenarioContinuationVerdict()).append('\n');
        return text.toString();
    }
}
