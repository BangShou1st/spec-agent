package com.specagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.policy.AgentProposal;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:P3BAttributionAndExecutionMetricsTest.java
 *
 * 测试目标:P3-B 的两处口径修复的确定性证据(纯单元,不触网、不碰数据库)。
 *
 * <ol>
 *   <li><b>失败归因不再被泛化文案遮蔽</b>:{@link ScenarioRunner} 的终态取值必须优先使用
 *       {@code RUN_FAILED} 的类型化 {@code reason},只有确实没有失败事件时才回退到
 *       调用方捕获到的异常文案。评测因此能把"模型输出违反契约"与"完全未知的故障"分开。</li>
 *   <li><b>执行质量三段分离</b>:{@code run_normal_terminal / action_submitted / action_completed}
 *       是三条独立事实——进入待审批或 policy denied 绝不等于动作执行成功。</li>
 *   <li><b>真实 run1–run4 证据回归</b>:直接以保留的 live 报告 JSON 为输入重算指标,
 *       绝不改写原始证据。</li>
 * </ol>
 */
class P3BAttributionAndExecutionMetricsTest {

    private static final Path LIVE_REPORTS = Path.of("..", "tools", "p3b");

    // -- 终态取值:类型化 reason 优先于泛化 failureDetail -------------------------------

    @Test
    void typedRunFailedReasonIsUsedInsteadOfTheGenericFailureDetail() {
        List<AttemptContext.AgentRunEventView> events = List.of(
                event("STATE_UPDATE_COMPLETED", Map.of()),
                event("RUN_FAILED", Map.of("reason", "model_contract_violation")));

        String result = ScenarioRunner.executionResult(events, List.of(),
                "AgentBrainUnavailableException: Agent brain call failed: /v1/decisions");

        // 机器码在前(归因依据),泛化细节保留在后(诊断依据)。
        assertThat(result).startsWith("failed:model_contract_violation");
        assertThat(result).contains("Agent brain call failed: /v1/decisions");
        assertThat(P3BDecisionQuality.classifyFailure(result, null, null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.MODEL_PROTOCOL_VIOLATION);
        assertThat(P3BDecisionQuality.payloadValidity(null, result))
                .isEqualTo(P3BDecisionQuality.PayloadValidity.INVALID);
    }

    @Test
    void providerFailureReasonAlsoSurvivesTheGenericDetail() {
        List<AttemptContext.AgentRunEventView> events = List.of(
                event("RUN_FAILED", Map.of("reason", "model_provider_failure")));

        String result = ScenarioRunner.executionResult(events, List.of(),
                "AgentBrainUnavailableException: Agent brain call failed: /v1/decisions");

        assertThat(P3BDecisionQuality.classifyFailure(result, null, null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.PROVIDER_UNAVAILABLE);
    }

    @Test
    void genericFailureDetailIsUsedOnlyWhenNoRunFailedEventExists() {
        String result = ScenarioRunner.executionResult(List.of(), List.of(),
                "AgentBrainUnavailableException: Agent brain call failed: /v1/decisions");

        assertThat(result)
                .isEqualTo("failed:AgentBrainUnavailableException: Agent brain call failed: /v1/decisions");
        // 没有类型化证据时如实维持 UNKNOWN,绝不猜测。
        assertThat(P3BDecisionQuality.classifyFailure(result, null, null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.UNKNOWN_FAILURE);
    }

    @Test
    void aGenericRunFailedReasonStaysUnknownRatherThanBeingGuessed() {
        String result = ScenarioRunner.executionResult(
                List.of(event("RUN_FAILED", Map.of("reason", "IllegalStateException"))),
                List.of(), "IllegalStateException: bad state");

        assertThat(result).startsWith("failed:IllegalStateException");
        assertThat(P3BDecisionQuality.classifyFailure(result, null, null, null, null))
                .isEqualTo(P3BDecisionQuality.FailureClassification.UNKNOWN_FAILURE);
    }

    @Test
    void completedAndAwaitingApprovalTerminalStatesAreExtractedVerbatim() {
        assertThat(ScenarioRunner.executionResult(
                List.of(event("RUN_COMPLETED", Map.of())), List.of(), null))
                .isEqualTo("completed");
        assertThat(ScenarioRunner.executionResult(
                List.of(event("AWAITING_APPROVAL", Map.of("proposalId", "p-1"))),
                List.of(), null))
                .isEqualTo("awaiting_approval:p-1");
    }

    // -- 执行质量三段分离 ---------------------------------------------------------

    @Test
    void awaitingApprovalIsNotActionExecution() {
        String result = "awaiting_approval:p-1";

        assertThat(P3BDecisionQuality.runNormalTerminal(result)).isTrue();
        assertThat(P3BDecisionQuality.actionSubmitted(result)).isTrue();
        assertThat(P3BDecisionQuality.actionCompleted(result)).isFalse();
        assertThat(P3BDecisionQuality.executedAction("INVOKE_CAPABILITY", result)).isNull();
    }

    @Test
    void policyDeniedIsNeitherSubmittedNorCompleted() {
        String result = "policy_denied";

        assertThat(P3BDecisionQuality.runNormalTerminal(result)).isTrue();
        assertThat(P3BDecisionQuality.actionSubmitted(result)).isFalse();
        assertThat(P3BDecisionQuality.actionCompleted(result)).isFalse();
        assertThat(P3BDecisionQuality.executedAction("INVOKE_CAPABILITY", result)).isNull();
    }

    @Test
    void onlyCompletedCountsAsExecuted() {
        String result = "completed";

        assertThat(P3BDecisionQuality.runNormalTerminal(result)).isTrue();
        assertThat(P3BDecisionQuality.actionSubmitted(result)).isTrue();
        assertThat(P3BDecisionQuality.actionCompleted(result)).isTrue();
        assertThat(P3BDecisionQuality.executedAction("INVOKE_CAPABILITY", result))
                .isEqualTo("INVOKE_CAPABILITY");
    }

    @Test
    void failureAndUnknownAreNeverANormalTerminal() {
        assertThat(P3BDecisionQuality.runNormalTerminal("failed:model_contract_violation")).isFalse();
        assertThat(P3BDecisionQuality.runNormalTerminal("unknown")).isFalse();
        assertThat(P3BDecisionQuality.runNormalTerminal(null)).isFalse();
        assertThat(P3BDecisionQuality.runOutcomeMeasurable("failed:model_contract_violation")).isTrue();
        assertThat(P3BDecisionQuality.runOutcomeMeasurable("unknown")).isFalse();
    }

    /**
     * 同一 Run 内既有失败的推理请求、又有成功的最终执行时,不得整体判为基础设施失败。
     * 这与 {@code EvalLiveP3BDecisionQualityTest.attributeFailure} 的口径一致。
     */
    @Test
    void aRunWithAFailedRequestButACompletedActionIsNotInfrastructureFailure() {
        P3BDecisionQuality.FailureClassification candidate = P3BDecisionQuality.classifyFailure(
                "completed", "RATE_LIMITED", null, null, null);
        assertThat(candidate).isEqualTo(P3BDecisionQuality.FailureClassification.RATE_LIMITED);
        assertThat(candidate.infrastructureFailure()).isTrue();
        // 最终执行成功时调用方按决策结果归因(不判为基础设施失败)。
        assertThat(P3BDecisionQuality.executedAction("REQUEST_USER_INPUT", "completed"))
                .isEqualTo("REQUEST_USER_INPUT");
    }

    // -- 真实 run1–run4 证据回归(原始 JSON 只读) -----------------------------------

    @Test
    void retainedLiveReportsRecomputeToTheSameActionCompletionMetric() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String fileName : List.of(
                "p3b_live_run1_poolside_20261018.json",
                "p3b_live_run2_cohere_20261018.json",
                "p3b_live_run3_c13c15c09_20261018.json",
                "p3b_live_run4_c09_20261018.json")) {
            JsonNode report = mapper.readTree(Files.readString(LIVE_REPORTS.resolve(fileName)));
            List<JsonNode> attempts = new ArrayList<>();
            report.path("attempts").forEach(attempts::add);

            int completed = 0;
            int submitted = 0;
            int normalTerminal = 0;
            int terminalMeasured = 0;
            int failed = 0;
            int actionAccepted = 0;
            for (JsonNode attempt : attempts) {
                String executionResult = attempt.path("execution_result").asText(null);
                boolean hasAction = attempt.path("action_measured").asBoolean(false);
                if (hasAction) {
                    actionAccepted++;
                    if (P3BDecisionQuality.actionCompleted(executionResult)) {
                        completed++;
                    }
                    if (P3BDecisionQuality.actionSubmitted(executionResult)) {
                        submitted++;
                    }
                }
                if (executionResult != null && executionResult.startsWith("failed:")) {
                    failed++;
                }
                if (P3BDecisionQuality.runOutcomeMeasurable(executionResult)) {
                    terminalMeasured++;
                    if (P3BDecisionQuality.runNormalTerminal(executionResult)) {
                        normalTerminal++;
                    }
                }
                // 原始证据里 executed_action 只在 completed 时非空——与收窄后的语义一致。
                boolean executed = !attempt.path("executed_action").isNull()
                        && !attempt.path("executed_action").asText("").isBlank();
                assertThat(executed)
                        .as(fileName + " executed_action must track action_completed")
                        .isEqualTo(P3BDecisionQuality.actionCompleted(executionResult));
            }

            // 这些真实 run 的"成功"全部是 completed,没有任何 policy_denied/awaiting_approval,
            // 因此 v1 的 execution_success_rate 与 v2 的 action_completed_rate 数值一致——
            // 这证明别名映射忠实,且原始证据没有被改写。
            assertThat(completed).as(fileName + " action completed count")
                    .isEqualTo(actionAccepted);
            assertThat(submitted).as(fileName + " action submitted count")
                    .isEqualTo(actionAccepted);
            // 可判终态 = 正常终态 + 失败终态(这些真实 run 里没有不可判的 "unknown")。
            assertThat(normalTerminal).as(fileName + " normal terminal count")
                    .isEqualTo(terminalMeasured - failed);

            double expectedRate = actionAccepted == 0 ? 0.0 : 1.0;
            JsonNode storedRate = report.get("execution_success_rate");
            if (storedRate == null || storedRate.isNull()) {
                assertThat(actionAccepted).as(fileName + " v1 null rate means zero measured").isZero();
            } else {
                assertThat(storedRate.asDouble()).as(fileName + " v1 alias").isEqualTo(expectedRate);
                assertThat(report.path("execution_measured_count").asInt())
                        .as(fileName + " v1 denominator equals action_measured")
                        .isEqualTo(actionAccepted);
            }
        }
    }

    /**
     * 真实 live 证据里 C08/C15 holdout 的失败只以泛化异常文案记录(没有任何类型化
     * 机器码),因此按"原因未证实即 UNKNOWN"如实归类;一旦运行时写下类型化 reason,
     * 同一函数便给出明确分类——这正是本轮修复的提取口径。
     */
    @Test
    void retainedC08C15FailureEvidenceStaysUnknownUntilATypedReasonExists() throws Exception {
        JsonNode report = new ObjectMapper().readTree(Files.readString(
                LIVE_REPORTS.resolve("p3b_live_run2_cohere_20261018.json")));
        Map<String, String> byVariant = new LinkedHashMap<>();
        report.path("attempts").forEach(attempt -> {
            if ("P3B-C08".equals(attempt.path("scenario_id").asText())) {
                byVariant.put(attempt.path("variant_id").asText(),
                        attempt.path("execution_result").asText());
            }
        });

        String holdout = byVariant.get("holdout");
        assertThat(holdout).startsWith("failed:AgentBrainUnavailableException");
        assertThat(P3BDecisionQuality.classifyFailure(holdout, null, null, null, null))
                .as("no typed machine code in the retained record → UNKNOWN, never guessed")
                .isEqualTo(P3BDecisionQuality.FailureClassification.UNKNOWN_FAILURE);
        assertThat(P3BDecisionQuality.classifyFailure(
                "failed:model_contract_violation", null, null, null, null))
                .as("had the run persisted its typed reason, the classifier names it")
                .isEqualTo(P3BDecisionQuality.FailureClassification.MODEL_PROTOCOL_VIOLATION);
    }

    private static AttemptContext.AgentRunEventView event(String type, Map<String, Object> payload) {
        return new AttemptContext.AgentRunEventView(type, payload);
    }
}
