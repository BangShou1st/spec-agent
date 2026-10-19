package com.specagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.trace.SemanticTrace;
import com.specagent.agent.trace.SemanticTraceSanitizer;
import com.specagent.model.contract.ModelProvider;
import com.sharedlibs.kilogateway.FreeModelPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:EvalLiveP3BDecisionQualityTest.java
 *
 * 真实模型 P3-B 决策质量证据(P3-B live)。
 *
 * 链路:Java Host → Python Project Agent Brain → Java Internal Model Broker →
 * <b>当前全局激活的提供商</b>(本轮为 Kilo Gateway)。绝不改成直连模型的测试替身,
 * 也绝不为省事换成脚本化 Brain。
 *
 * 数值口径:
 * <ul>
 *   <li>模型选择准确率以<b>原始模型选择</b>(DECISION_OUTPUT / ACTION_ELIGIBILITY.selected_action)
 *       为依据,绝不只统计通过 Java 门禁的动作;</li>
 *   <li>门禁准确率与执行成功率<b>各自独立统计</b>,每个比率都带显式分母;</li>
 *   <li>不可观测的字段记 {@code NOT_MEASURED},绝不按正确或错误填充;</li>
 *   <li>同一 Run 同时存在失败与成功请求时,按请求证据与最终执行证据分别归因,
 *       不因第一个失败类别就把整个 Run 判为基础设施失败。</li>
 * </ul>
 *
 * 请求纪律:初次评测严格限制请求总量;遇 429 / 认证错误 / 持续提供商故障立即停止,
 * 不自动重试、不自动换模型。使用<b>隔离数据库</b>,绝不改动用户开发库的激活模型设置。
 *
 * <p>模型设置取自<b>隔离数据库</b>的当前全局激活提供商(test 前显式置为 Kilo);
 * 不再使用 OpenCode Zen 专属的外置环境配置。{@code model.inference=opencode} 只是
 * 打开权威路由网关({@code RoutingModelInferenceGateway})的主开关,与具体提供商无关。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18082",
                "spec.agent.brain.engine=remote-python",
                "spec.agent.brain.base-url=${SPEC_AGENT_EVAL_BRAIN_BASE_URL:http://localhost:8100}",
                "spec.agent.brain.internal-secret=${SPEC_AGENT_BRAIN_INTERNAL_SECRET:dev-internal-secret}",
                "spec.agent.model.inference=opencode",
                "spec.agent.semantic-trace.enabled=true"
        })
class EvalLiveP3BDecisionQualityTest extends EvalLiveHarnessBase {

    /** 明确的 Kilo 模型 ID;不用 kilo-auto/free 等动态路由模型做固定质量比较。 */
    private static final String KILO_MODEL = System.getenv().getOrDefault(
            "SPEC_AGENT_EVAL_KILO_MODEL", "cohere/north-mini-code:free");

    /** 本轮 Harness 的请求总量上限(含 STATE_UPDATE + DECISION);独立实验可收紧预算。 */
    private static final int MAX_TOTAL_REQUESTS = maxTotalRequests();

    private static int maxTotalRequests() {
        String configured = System.getenv().getOrDefault(
                "SPEC_AGENT_EVAL_P3B_MAX_TOTAL_REQUESTS", "64");
        int parsed = Integer.parseInt(configured);
        if (parsed < 1) {
            throw new IllegalArgumentException(
                    "SPEC_AGENT_EVAL_P3B_MAX_TOTAL_REQUESTS must be at least 1");
        }
        return parsed;
    }
    private static final String EXPERIMENT_ID = System.getenv().getOrDefault(
            "SPEC_AGENT_EVAL_P3B_EXPERIMENT", "p3b-live-priority");
    private static final String PROMPT_RULE_SET = System.getenv().getOrDefault(
            "SPEC_AGENT_EVAL_P3B_PROMPT_RULES", "default");

    private static final java.util.UUID LOCAL_ORG =
            java.util.UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** 优先场景:C03 改写重复追问 / C07 相关但不必要 / C13 作废来源 / C15 无依据断言。 */
    private static final List<String> PRIORITY_IDS =
            List.of("P3B-C03", "P3B-C07", "P3B-C13", "P3B-C15");
    /** 必要的工具调用:验证 C08(参数齐全必须被允许)与 C09(缺必填参数必须 fail-closed)。 */
    private static final List<String> GUARD_IDS = List.of("P3B-C08", "P3B-C09");

    /**
     * 本次实际运行的场景 id 列表,按声明顺序执行(不受语料 C01..C18 顺序影响)。
     * 可用 {@code SPEC_AGENT_EVAL_P3B_SCENARIOS} 覆盖(逗号分隔),便于在受限请求预算下
     * 分批覆盖优先场景;默认=优先场景 + 守卫场景。
     */
    private static final List<String> SCENARIO_IDS = resolveScenarioIds();

    private static List<String> resolveScenarioIds() {
        String raw = System.getenv().getOrDefault("SPEC_AGENT_EVAL_P3B_SCENARIOS", "");
        if (raw != null && !raw.isBlank()) {
            List<String> ids = new ArrayList<>();
            for (String token : raw.split(",")) {
                String id = token.trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        }
        List<String> ids = new ArrayList<>(PRIORITY_IDS);
        ids.addAll(GUARD_IDS);
        return List.copyOf(ids);
    }

    private static final String BRAIN_HEALTH = System.getenv().getOrDefault(
            "SPEC_AGENT_EVAL_BRAIN_BASE_URL", "http://localhost:8100") + "/health";

    @Autowired
    private ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Value(
            "${spec.agent.action-eligibility.mode:unknown}")
    private String actionEligibilityMode;

    /** 数据源配置里声明的库名;用于与服务器端真实库身份比对(见 EvalLiveIsolatedDatabase)。 */
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault(
                "SPEC_AGENT_EVAL_DB_URL",
                "jdbc:postgresql://localhost:5434/spec_agent_p3b_live_20261018");
        registry.add("spring.datasource.url", () -> url);
    }

    @Test
    void priorityScenariosRecordRealModelDecisionEvidence() throws Exception {
        EvalLiveIsolatedDatabase.DatabaseIdentity databaseIdentity =
                EvalLiveIsolatedDatabase.readIdentity(jdbcTemplate);
        EvalLiveIsolatedDatabase.requireIsolatedLiveEvalDatabase(
                databaseIdentity,
                EvalLiveIsolatedDatabase.databaseNameFromUrl(datasourceUrl));
        String evalDatabase = seedKiloActivation();
        assertThat(evalDatabase).isEqualTo(databaseIdentity.database());
        LiveChainEvidence before = requireLiveProvider(BRAIN_HEALTH);
        assertThat(before.provider()).isEqualTo(ModelProvider.KILO.name());
        assertThat(before.selectedModel()).isEqualTo(KILO_MODEL);
        assertThat(FreeModelPolicy.isFreeModelId(KILO_MODEL))
                .as("fixed quality comparison uses an explicitly named free model")
                .isTrue();

        Path output = reportPath();
        Path attemptSidecar = output.resolveSibling(output.getFileName() + ".attempts.jsonl");
        Files.createDirectories(output.getParent());
        Files.writeString(attemptSidecar, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        List<Map<String, Object>> attemptReports = new ArrayList<>();
        List<P3BDecisionQuality.DecisionObservation> qualityObservations = new ArrayList<>();
        Map<String, P3BCorpus.P3BOracle> oracles = new LinkedHashMap<>();
        Map<String, P3BCorpus.P3BScenario> byId = new LinkedHashMap<>();
        for (P3BCorpus.P3BScenario scenario : P3BCorpus.all()) {
            byId.put(scenario.scenarioId(), scenario);
        }
        boolean rateLimited = false;
        boolean stoppedAfterProviderFailure = false;
        boolean stoppedAfterProviderRetry = false;
        boolean stoppedAfterUnclassifiedFailure = false;
        boolean stoppedOnRequestBudget = false;
        int totalRequests = 0;
        outer:
        for (String scenarioId : SCENARIO_IDS) {
            P3BCorpus.P3BScenario scenario = byId.get(scenarioId);
            if (scenario == null) {
                throw new IllegalArgumentException("Unknown P3-B scenario: " + scenarioId);
            }
            oracles.put(scenario.scenarioId(), scenario.oracle());
            for (VariantSpec variant : scenario.definition().variants()) {
                if (totalRequests >= MAX_TOTAL_REQUESTS) {
                    stoppedOnRequestBudget = true;
                    break outer;
                }
                long startedAt = System.nanoTime();
                ObservationEnvelope observation = runLiveScenario(
                        scenario.definition(), variant, 1).get(0);
                long measuredDurationMs = (System.nanoTime() - startedAt) / 1_000_000;
                UUID projectId = scenarioRunner.lastProjectId();
                ModelRequestEvidence requestEvidence = modelRequestEvidence(observation.attemptId());
                totalRequests += requestEvidence.requestCount();
                int capabilityCalls = persistedCapabilityInvocations(projectId);

                Map<String, Object> eligibility = stage(
                        observation.semanticTrace(), "ACTION_ELIGIBILITY");
                String modelSelected = modelSelectedAction(observation.semanticTrace());
                String acceptedAction = observation.actualPrimaryAction();
                String executionResult = observation.executionResult();
                String executedAction = P3BDecisionQuality.executedAction(
                        acceptedAction, executionResult);

                P3BDecisionQuality.FailureClassification failure =
                        attributeFailure(scenario, acceptedAction, executionResult,
                                requestEvidence, eligibility, observation.semanticTrace());

                boolean modelSelectionMeasured = modelSelected != null && !modelSelected.isBlank();
                boolean modelSelectionAcceptable = modelSelectionMeasured
                        && scenario.definition().expect().acceptablePrimaryActions()
                        .contains(modelSelected);
                boolean actionAcceptable = acceptedAction != null
                        && scenario.definition().expect().acceptablePrimaryActions()
                        .contains(acceptedAction);
                boolean unnecessaryClarification = "P3B-C03".equals(scenario.scenarioId())
                        && "REQUEST_USER_INPUT".equals(acceptedAction);
                boolean unnecessaryCapability = "P3B-C07".equals(scenario.scenarioId())
                        && ("INVOKE_CAPABILITY".equals(acceptedAction) || capabilityCalls > 0);
                boolean semanticErrorSignal = acceptedAction != null
                        && (!actionAcceptable || unnecessaryClarification || unnecessaryCapability);

                qualityObservations.add(new P3BDecisionQuality.DecisionObservation(
                        scenario.scenarioId(), variant.variantId(),
                        modelSelected, modelSelectionAcceptable,
                        acceptedAction, executedAction, executionResult,
                        actionAcceptable,
                        P3BDecisionQuality.gateOutcome(eligibility),
                        P3BDecisionQuality.gateReasonCodes(eligibility),
                        P3BDecisionQuality.payloadValidity(acceptedAction, executionResult),
                        false, false, null,
                        scenario.oracle().capabilityNecessityScored()
                                && failure == P3BDecisionQuality.FailureClassification.NONE,
                        !unnecessaryCapability,
                        scenario.definition().expect().forbiddenActions()
                                .contains("REQUEST_USER_INPUT"),
                        unnecessaryClarification,
                        false, false,
                        requestEvidence.requestCount(), capabilityCalls, failure));

                Map<String, Object> attempt = new LinkedHashMap<>();
                attempt.put("scenario_id", scenario.scenarioId());
                attempt.put("variant_id", variant.variantId());
                attempt.put("run_correlation_id", safeCorrelationId(observation.attemptId()));
                attempt.put("scenario_hash", scenario.definition().scenarioHash());
                attempt.put("candidate_label_status", "PENDING_HUMAN_REVIEW");
                attempt.put("oracle_candidate", Map.of(
                        "acceptable_primary_actions", scenario.definition().expect().acceptablePrimaryActions(),
                        "forbidden_actions", scenario.definition().expect().forbiddenActions(),
                        "expected_gate", scenario.oracle().gate().name(),
                        "expected_veto_reason_codes", scenario.oracle().vetoReasonCodes()));
                attempt.put("model_request_events", requestEvidence.events());
                attempt.put("run_failed_evidence", runFailedEvidence(observation.attemptId()));
                attempt.put("input_evidence", inputEvidence(observation.semanticTrace(), scenario));
                attempt.put("model_selected_action", modelSelected);
                attempt.put("model_selection_measured", modelSelectionMeasured);
                attempt.put("model_selection_acceptable", modelSelectionAcceptable);
                attempt.put("eligibility_outcome",
                        P3BDecisionQuality.gateOutcome(eligibility).name());
                attempt.put("eligibility_reason_codes",
                        P3BDecisionQuality.gateReasonCodes(eligibility));
                attempt.put("accepted_action", acceptedAction);
                attempt.put("executed_action", executedAction);
                attempt.put("action_acceptable", actionAcceptable);
                attempt.put("action_measured", acceptedAction != null
                        && !acceptedAction.isBlank());
                attempt.put("payload_validity", P3BDecisionQuality.payloadValidity(
                        acceptedAction, executionResult).name());
                attempt.put("execution_state", safeExecutionState(executionResult));
                attempt.put("failure_classification", failure.name());
                attempt.put("model_request_count", requestEvidence.requestCount());
                attempt.put("successful_model_requests", requestEvidence.successCount());
                attempt.put("failed_model_requests", requestEvidence.failedCount());
                attempt.put("provider_retries", observation.providerRetries());
                attempt.put("judge_model_calls", observation.judgeModelCalls());
                attempt.put("reported_production_model_calls", observation.productionModelCalls());
                attempt.put("provider_failure_categories", requestEvidence.failureCategories());
                attempt.put("attribution_basis", attributionBasis(
                        acceptedAction, requestEvidence, failure));
                attempt.put("capability_calls", capabilityCalls);
                attempt.put("repeated_clarification", unnecessaryClarification);
                attempt.put("unnecessary_capability_call", unnecessaryCapability);
                attempt.put("unresolved_semantic_error_signal", semanticErrorSignal);
                attempt.put("manual_semantic_review_required",
                        Set.of("P3B-C03", "P3B-C13", "P3B-C15").contains(scenario.scenarioId()));
                attempt.put("model_raw_action", sanitizedModelAction(observation.semanticTrace()));
                attempt.put("prompt_hashes", promptHashes(observation.semanticTrace()));
                attempt.put("source_reference_audit", sourceReferenceAudit(observation.semanticTrace()));
                attempt.put("automatic_semantic_review", automaticSemanticReview(
                        scenario, modelSelected, observation.semanticTrace(), requestEvidence,
                        failure, executionResult, capabilityCalls));
                attempt.put("duration_ms", observation.latencyMs() == null
                        ? measuredDurationMs : observation.latencyMs());
                attempt.put("measured_duration_ms", measuredDurationMs);
                attempt.put("stage_latency_ms", observation.stageLatencyMs());
                attemptReports.add(attempt);
                Files.writeString(attemptSidecar,
                        mapper.writeValueAsString(attempt) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);

                if (failure == P3BDecisionQuality.FailureClassification.RATE_LIMITED) {
                    rateLimited = true;
                    break outer;
                }
                if (failure.infrastructureFailure()) {
                    stoppedAfterProviderFailure = true;
                    break outer;
                }
                if (observation.providerRetries() > 0) {
                    stoppedAfterProviderRetry = true;
                    break outer;
                }
                if (acceptedAction == null
                        && (requestEvidence.failedCount() > 0 || failure
                        == P3BDecisionQuality.FailureClassification.UNKNOWN_FAILURE)) {
                    stoppedAfterUnclassifiedFailure = true;
                    break outer;
                }
            }
        }

        P3BDecisionQuality.Summary summary = P3BDecisionQuality.summarize(
                qualityObservations, oracles);
        LiveBrainHealth after = readLiveBrainHealth(BRAIN_HEALTH);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", "p3b-live-priority.v3");
        report.put("schema_migration",
                "v1 execution_success_rate 把 Run 正常终态(policy_denied/awaiting_approval)与"
                        + "动作实际执行混为一谈;v2 拆分为 run_normal_terminal_rate /"
                        + " action_submitted_rate / action_completed_rate,并保留 execution_success_rate"
                        + " 作为 action_completed_rate 的兼容别名(v1 的分母等于 action_measured_count)。"
                        + "v1 的 executed_action 也据此收窄:只有 completed 才记为已执行。");
        report.put("suite", "EvalLiveP3BDecisionQualityTest");
        report.put("experiment_id", EXPERIMENT_ID);
        report.put("arm", System.getenv().getOrDefault("SPEC_AGENT_EVAL_P3B_ARM", "UNSPECIFIED"));
        report.put("prompt_rule_set", PROMPT_RULE_SET);
        report.put("prompt_version_basis", "DECISION system prompt SHA-256 in each attempt");
        report.put("eval_database", evalDatabase);
        report.put("eval_database_identity", Map.of(
                "database", databaseIdentity.database(),
                "user", databaseIdentity.user(),
                "host", databaseIdentity.host(),
                "port", databaseIdentity.port()));
        report.put("provider", before.provider());
        report.put("model_id", before.selectedModel());
        report.put("model_listed_as_free",
                FreeModelPolicy.isFreeModelId(before.selectedModel()));
        report.put("endpoint", before.endpoint());
        report.put("credential_source", before.credentialSource());
        report.put("brain_model_mode", before.pythonBefore().modelMode());
        report.put("action_eligibility_mode", actionEligibilityMode());
        report.put("scenario_count", SCENARIO_IDS.size());
        report.put("scenario_ids", SCENARIO_IDS);
        report.put("attempt_count", attemptReports.size());
        report.put("expected_attempt_count", SCENARIO_IDS.size() * 2);
        report.put("report_status", attemptReports.size() == SCENARIO_IDS.size() * 2
                && !rateLimited && !stoppedAfterProviderFailure && !stoppedAfterProviderRetry
                && !stoppedAfterUnclassifiedFailure
                && !stoppedOnRequestBudget ? "COMPLETE" : "INCOMPLETE");
        report.put("human_review_status", "PENDING_HUMAN_REVIEW");
        report.put("oracle_policy", "Frozen AI candidate labels; this report does not approve or modify them");
        report.put("request_budget", Map.of(
                "experiment_max_new_upstream_requests", MAX_TOTAL_REQUESTS,
                "requests_in_this_arm", totalRequests,
                "judge_requests", 0,
                "retry_policy", "No explicit retries or provider fallback; actual inference events are counted"));
        report.put("stopped_after_rate_limit", rateLimited);
        report.put("stopped_after_provider_failure", stoppedAfterProviderFailure);
        report.put("stopped_after_provider_retry", stoppedAfterProviderRetry);
        report.put("stopped_after_unclassified_failure", stoppedAfterUnclassifiedFailure);
        report.put("stopped_on_request_budget", stoppedOnRequestBudget);
        report.put("total_model_requests", totalRequests);
        report.put("successful_model_requests", attemptReports.stream()
                .mapToLong(attempt -> ((Number) attempt.getOrDefault("successful_model_requests", 0)).longValue())
                .sum());
        report.put("failed_model_requests", attemptReports.stream()
                .mapToLong(attempt -> ((Number) attempt.getOrDefault("failed_model_requests", 0)).longValue())
                .sum());
        report.put("not_measured_action_count", attemptReports.stream()
                .filter(attempt -> !Boolean.TRUE.equals(attempt.get("action_measured"))).count());
        report.put("protocol_failure_count", summary.protocolFailures());
        report.put("sanitization_policy", "No raw run IDs, full inputs, private prompts, tokens, or HTTP requests; prompt hashes and fixture-only action payloads retained.");
        // 三组独立指标,每组都带显式分母。
        report.put("model_selection_accuracy", summary.modelSelectionAccuracy());
        report.put("model_selection_measured_count", summary.modelSelectionMeasuredCount());
        report.put("model_selection_not_measured_count", summary.modelSelectionNotMeasuredCount());
        report.put("gate_accuracy", summary.gateAccuracy());
        report.put("gate_measured_count", summary.gateMeasuredCount());
        // 三组独立指标:Run 终态 / 动作提交 / 动作完成,各自带显式分母。
        report.put("run_normal_terminal_rate", summary.runNormalTerminalRate());
        report.put("run_normal_terminal_measured_count", summary.runNormalTerminalMeasuredCount());
        report.put("action_submitted_rate", summary.actionSubmittedRate());
        report.put("action_submitted_measured_count", summary.actionSubmittedMeasuredCount());
        report.put("action_completed_rate", summary.actionCompletedRate());
        report.put("action_completed_measured_count", summary.actionCompletedMeasuredCount());
        // v1 兼容别名(= 动作完成率及其分母);见 schema_migration。
        report.put("execution_success_rate", summary.actionCompletedRate());
        report.put("execution_measured_count", summary.actionCompletedMeasuredCount());
        // 保留既有 action acceptable-set 口径(以被接受动作为依据)。
        report.put("action_acceptable_set_accuracy", summary.actionAcceptableSetAccuracy());
        report.put("action_acceptable_set_denominator", summary.actionMeasuredCount());
        report.put("action_measured_count", summary.actionMeasuredCount());
        report.put("repeated_clarification_count", count(attemptReports, "repeated_clarification"));
        report.put("repeated_clarification_measured_count", measuredCount(
                attemptReports, "P3B-C03"));
        report.put("unnecessary_capability_call_count",
                count(attemptReports, "unnecessary_capability_call"));
        report.put("unnecessary_capability_call_measured_count", measuredCount(
                attemptReports, "P3B-C07"));
        report.put("capability_call_count", summary.totalCapabilityCalls());
        report.put("payload_validity", summary.payloadValidity());
        report.put("payload_validity_measured", summary.payloadValidityMeasuredCount());
        report.put("payload_validity_invalid_count",
                attemptReports.stream().filter(attempt -> "INVALID".equals(
                        attempt.get("payload_validity"))).count());
        report.put("eligibility_veto_count",
                attemptReports.stream().filter(attempt -> "VETOED".equals(
                        attempt.get("eligibility_outcome"))).count());
        long eligibilityUnknownCount = attemptReports.stream()
                .filter(attempt -> "UNKNOWN".equals(attempt.get("eligibility_outcome"))).count();
        report.put("eligibility_unknown_count", eligibilityUnknownCount);
        report.put("eligibility_measured_count", attemptReports.size() - eligibilityUnknownCount);
        report.put("unresolved_semantic_error_signal_count",
                count(attemptReports, "unresolved_semantic_error_signal"));
        report.put("unresolved_semantic_error_signal_measured_count", attemptReports.stream()
                .filter(attempt -> Boolean.TRUE.equals(attempt.get("action_measured"))).count());
        report.put("manual_semantic_review_required_count",
                count(attemptReports, "manual_semantic_review_required"));
        report.put("failure_classification_breakdown", summary.failureClassificationBreakdown());
        report.put("protocol_error_count", summary.protocolFailures());
        report.put("rate_limited_count", summary.failureClassificationBreakdown()
                .getOrDefault("RATE_LIMITED", 0));
        report.put("infrastructure_failure_count", summary.infraFailures());
        report.put("unknown_failure_count", summary.unknownFailureCount());
        report.put("attempts", attemptReports);
        report.put("attempt_evidence_sidecar", attemptSidecar.toString());
        report.put("python_state_update_delta",
                after.stateUpdates() - before.pythonBefore().stateUpdates());
        report.put("python_decision_delta",
                after.decisions() - before.pythonBefore().decisions());

        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("P3-B live priority report: " + output.toAbsolutePath());
        System.out.println("P3-B live provider=" + before.provider()
                + " model=" + before.selectedModel()
                + " attempts=" + attemptReports.size()
                + " requests=" + totalRequests
                + " modelSelectionAccuracy=" + summary.modelSelectionAccuracy()
                + " gateAccuracy=" + summary.gateAccuracy()
                + " runNormalTerminal=" + summary.runNormalTerminalRate()
                + " actionSubmitted=" + summary.actionSubmittedRate()
                + " actionCompleted=" + summary.actionCompletedRate()
                + " failures=" + summary.failureClassificationBreakdown());
    }

    /**
     * 隔离库准备:先核实真实库身份,再激活 Kilo 并把固定模型写入 kilo_settings。
     * 任何 DELETE / INSERT 都在身份校验<b>之后</b>才发生——非独立 P3-B live 评测库
     * 一律失败关闭,一行数据也不改(见 {@link EvalLiveIsolatedDatabase})。
     *
     * @return 已核实的真实库名(写入报告留证)
     */
    private String seedKiloActivation() {
        return EvalLiveIsolatedDatabase.seedKiloActivation(
                jdbcTemplate,
                EvalLiveIsolatedDatabase.databaseNameFromUrl(datasourceUrl),
                KILO_MODEL,
                LOCAL_ORG);
    }

    private String actionEligibilityMode() {
        return actionEligibilityMode;
    }

    /**
     * 归因:先看最终执行证据。只要本次尝试产出了被 Java 接受的动作,就不是基础设施
     * 失败——即使中途有失败的推理请求(如一次重试后成功),也按决策结果归因。只有
     * 完全没有动作产出时,才用类型化的 provider 类别、trace 错误与门禁裁决分类。
     */
    private P3BDecisionQuality.FailureClassification attributeFailure(
            P3BCorpus.P3BScenario scenario, String acceptedAction, String executionResult,
            ModelRequestEvidence requestEvidence, Map<String, Object> eligibilityStage,
            SemanticTrace trace) {
        P3BDecisionQuality.FailureClassification candidate =
                P3BDecisionQuality.classifyFailure(
                        executionResult,
                        requestEvidence.failureCategories().stream().findFirst().orElse(null),
                        eligibilityStage,
                        traceError(trace, "error_type"),
                        traceError(trace, "error_summary"));
        if (acceptedAction != null && !acceptedAction.isBlank() && candidate.infrastructureFailure()) {
            // 同一 Run 里既有失败请求又有成功的最终执行:不得整体判为基础设施失败。
            return P3BDecisionQuality.FailureClassification.NONE;
        }
        return candidate;
    }

    private static String attributionBasis(String acceptedAction,
                                           ModelRequestEvidence requestEvidence,
                                           P3BDecisionQuality.FailureClassification failure) {
        if (acceptedAction != null && !acceptedAction.isBlank()) {
            return requestEvidence.failedCount() > 0
                    ? "final_execution_with_prior_failed_requests"
                    : "final_execution";
        }
        return "request_evidence:" + failure.name();
    }

    private static int measuredCount(List<Map<String, Object>> attempts, String scenarioId) {
        return (int) attempts.stream()
                .filter(attempt -> scenarioId.equals(attempt.get("scenario_id")))
                .filter(attempt -> Boolean.TRUE.equals(attempt.get("action_measured")))
                .count();
    }

    private ModelRequestEvidence modelRequestEvidence(String runId) {
        if (runId == null || runId.isBlank()) {
            return new ModelRequestEvidence(0, 0, 0, List.of(), List.of());
        }
        List<String> categories = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        int[] counts = new int[3];
        jdbcTemplate.query(
                "SELECT event_type, payload ->> 'callType', payload ->> 'gatewayCategory' "
                        + "FROM agent_run_events "
                        + "WHERE run_id = ? AND event_type IN ('MODEL_INFERENCE', "
                        + "'MODEL_INFERENCE_FAILED') ORDER BY sequence",
                resultSet -> {
                    while (resultSet.next()) {
                        String eventType = resultSet.getString(1);
                        String callType = resultSet.getString(2);
                        String category = resultSet.getString(3);
                        boolean succeeded = "MODEL_INFERENCE".equals(eventType);
                        counts[0]++;
                        if (succeeded) {
                            counts[1]++;
                        } else {
                            counts[2]++;
                            if (category != null && !categories.contains(category)) {
                                categories.add(category);
                            }
                        }
                        Map<String, Object> evidence = new LinkedHashMap<>();
                        evidence.put("call_type", callType);
                        evidence.put("status", succeeded ? "SUCCESS" : "FAILED");
                        evidence.put("gateway_category", category);
                        events.add(evidence);
                    }
                    return null;
                }, UUID.fromString(runId));
        return new ModelRequestEvidence(counts[0], counts[1], counts[2], categories, events);
    }

    /** 在逐项目清理前读取终态机器码;不保留自由文本 summary 或恢复定位 ID。 */
    private Map<String, Object> runFailedEvidence(String runId) {
        if (runId == null || runId.isBlank()) {
            return Map.of("present", false, "reason", "NOT_MEASURED", "error_code", "NOT_MEASURED");
        }
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT id AS event_id, payload ->> 'reason' AS reason, "
                        + "payload ->> 'errorCode' AS error_code, "
                        + "payload #>> '{contractDiagnostic,diagnosticId}' AS diagnostic_id, "
                        + "payload #>> '{contractDiagnostic,stage}' AS failure_stage, "
                        + "payload #>> '{contractDiagnostic,validatorId}' AS validator_id, "
                        + "payload #>> '{contractDiagnostic,machineCode}' AS diagnostic_code, "
                        + "payload #>> '{contractDiagnostic,fieldPath}' AS field_path, "
                        + "payload #>> '{contractDiagnostic,expectedType}' AS expected_type, "
                        + "payload #>> '{contractDiagnostic,actualType}' AS actual_type, "
                        + "payload #>> '{contractDiagnostic,boundary}' AS boundary, "
                        + "payload #>> '{contractDiagnostic,javaWireValidatorEntered}' AS java_wire_validator_entered, "
                        + "payload #>> '{contractDiagnostic,executorReached}' AS executor_reached, "
                        + "payload #>> '{contractDiagnostic,sourceExceptionType}' AS source_exception_type "
                        + "FROM agent_run_events WHERE run_id = ? AND event_type = 'RUN_FAILED' "
                        + "ORDER BY sequence",
                (resultSet, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("event_id", resultSet.getObject("event_id").toString());
                    row.put("reason", safeMachineCode(resultSet.getString("reason")));
                    row.put("error_code", safeMachineCode(resultSet.getString("error_code")));
                    row.put("diagnostic_id", safeUuid(resultSet.getString("diagnostic_id")));
                    row.put("failure_stage", safeMachineCode(resultSet.getString("failure_stage")));
                    row.put("validator_id", safeMachineCode(resultSet.getString("validator_id")));
                    row.put("diagnostic_code", safeMachineCode(resultSet.getString("diagnostic_code")));
                    row.put("field_path", safeDiagnosticPath(resultSet.getString("field_path")));
                    row.put("expected_type", safeDiagnosticToken(resultSet.getString("expected_type")));
                    row.put("actual_type", safeDiagnosticToken(resultSet.getString("actual_type")));
                    row.put("boundary", safeMachineCode(resultSet.getString("boundary")));
                    row.put("java_wire_validator_entered",
                            safeBoolean(resultSet.getString("java_wire_validator_entered")));
                    row.put("executor_reached", safeBoolean(resultSet.getString("executor_reached")));
                    row.put("source_exception_type",
                            safeMachineCode(resultSet.getString("source_exception_type")));
                    return row;
                },
                UUID.fromString(runId));
        if (rows.isEmpty()) {
            return Map.of("present", false, "reason", "NONE", "error_code", "NONE");
        }
        return Map.of("present", true, "events", rows);
    }

    private static String safeMachineCode(String value) {
        if (value == null || value.isBlank()) {
            return "NOT_RECORDED";
        }
        return value.matches("[A-Za-z0-9_]{1,80}") ? value : "UNCLASSIFIED_REDACTED";
    }

    private static String safeUuid(String value) {
        if (value == null || value.isBlank()) return "NOT_RECORDED";
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ignored) {
            return "UNCLASSIFIED_REDACTED";
        }
    }

    private static String safeDiagnosticPath(String value) {
        if (value == null || value.isBlank()) return "NOT_RECORDED";
        return value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}(?:\\.[A-Za-z_][A-Za-z0-9_]{0,63}|\\[[0-9]{1,5}]){0,15}")
                ? value : "UNCLASSIFIED_REDACTED";
    }

    private static String safeDiagnosticToken(String value) {
        if (value == null || value.isBlank()) return "NOT_RECORDED";
        return value.matches("[A-Za-z0-9 _-]{1,80}") ? value : "UNCLASSIFIED_REDACTED";
    }

    private static String safeBoolean(String value) {
        return "true".equals(value) || "false".equals(value) ? value : "UNKNOWN";
    }

    /** 只留下结构证据摘要;不把完整事实、answer 正文或私人工作区内容写进报告。 */
    private static Map<String, Object> inputEvidence(
            SemanticTrace trace, P3BCorpus.P3BScenario scenario) {
        Map<String, Object> decisionStage = stage(trace, "DECISION_INPUT");
        Map<String, Object> input = asMap(decisionStage.get("model_input"));
        if (input.isEmpty()) input = asMap(decisionStage.get("runtime_request"));
        Map<String, Object> snapshot = asMap(input.get("snapshot"));
        List<?> lineage = asList(snapshot.get("lineage"));
        long answeredEntries = lineage.stream().filter(entry -> {
            Map<String, Object> item = asMap(entry);
            return item.get("answer") instanceof Map<?, ?>
                    || !asList(item.get("patches")).isEmpty();
        }).count();
        List<?> claims = asList(snapshot.get("effectiveClaims"));
        long unresolvedClaims = claims.stream().filter(claim ->
                "unresolved".equalsIgnoreCase(asMap(claim).get("status") == null
                        ? "" : String.valueOf(asMap(claim).get("status")))).count();
        List<String> allowedRefs = stringList(snapshot.get("allowedSourceRefs"));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("answered_lineage_entries", answeredEntries);
        evidence.put("unresolved_effective_claims", unresolvedClaims);
        evidence.putAll(P3BSafeCapabilityEvidence.summarize(
                asList(snapshot.get("availableCapabilities")), scenario));
        evidence.put("allowed_source_ref_count", allowedRefs.size());
        evidence.put("allowed_source_refs_sha256", sha256(String.join("\n", allowedRefs.stream().sorted().toList())));
        evidence.put("retrieved_context_count", asList(snapshot.get("retrievedContext")).size());
        return evidence;
    }

    private static Map<String, Object> sanitizedModelAction(SemanticTrace trace) {
        Map<String, Object> normalized = asMap(stage(trace, "DECISION_OUTPUT").get("normalized_output"));
        if (normalized.isEmpty()) {
            return Map.of("status", "NOT_MEASURED");
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action_family", normalized.getOrDefault("actionFamily", "NOT_MEASURED"));
        Object payload = normalized.get("payload");
        action.put("payload", SemanticTraceSanitizer.sanitize(payload == null ? Map.of() : payload));
        action.put("source_ref_count", stringList(normalized.get("sourceRefs")).size());
        return action;
    }

    private static Map<String, Object> promptHashes(SemanticTrace trace) {
        Map<String, Object> hashes = new LinkedHashMap<>();
        for (String stageName : List.of("STATE_UPDATE_INPUT", "DECISION_INPUT")) {
            Map<String, Object> prompt = asMap(stage(trace, stageName).get("prompt"));
            hashes.put(stageName.toLowerCase(), prompt.getOrDefault(
                    "system_prompt_sha256", "NOT_MEASURED"));
        }
        return hashes;
    }

    private static Map<String, Object> sourceReferenceAudit(SemanticTrace trace) {
        Map<String, Object> decisionStage = stage(trace, "DECISION_INPUT");
        Map<String, Object> input = asMap(decisionStage.get("model_input"));
        if (input.isEmpty()) input = asMap(decisionStage.get("runtime_request"));
        Map<String, Object> snapshot = asMap(input.get("snapshot"));
        Map<String, Object> normalized = asMap(stage(trace, "DECISION_OUTPUT").get("normalized_output"));
        if (normalized.isEmpty()) {
            return Map.of("status", "NOT_MEASURED", "source_refs_allowed", "NOT_MEASURED",
                    "source_ref_count", 0, "anchor_ref_count", 0,
                    "invalidated_source_ref_count", 0,
                    "invalidated_source_reference_used", "NOT_MEASURED");
        }
        List<String> allowed = stringList(snapshot.get("allowedSourceRefs"));
        List<String> refs = stringList(normalized.get("sourceRefs"));
        List<String> anchors = stringList(normalized.get("anchorRefs"));
        boolean valid = allowed.containsAll(refs) && allowed.containsAll(anchors);
        Set<String> invalidated = invalidatedSourceRefs(snapshot);
        long invalidatedCount = java.util.stream.Stream.concat(refs.stream(), anchors.stream())
                .filter(invalidated::contains).distinct().count();
        return Map.of(
                "status", snapshot.isEmpty() ? "PARTIALLY_MEASURED" : "DETERMINISTIC_VERIFIED",
                "source_refs_allowed", valid,
                "source_ref_count", refs.size(),
                "anchor_ref_count", anchors.size(),
                "source_ref_manifest_sha256", sha256(String.join("\n", refs.stream().sorted().toList())),
                "invalidated_source_ref_count", invalidatedCount,
                "invalidated_source_reference_used", invalidatedCount > 0);
    }

    private static Set<String> invalidatedSourceRefs(Map<String, Object> snapshot) {
        java.util.Set<String> invalidated = new java.util.LinkedHashSet<>();
        for (Object value : asList(snapshot.get("retrievedContext"))) {
            Map<String, Object> item = asMap(value);
            String status = nullableString(item.get("status"));
            Object current = item.get("isCurrent");
            if ("stale".equalsIgnoreCase(status) || "superseded".equalsIgnoreCase(status)
                    || "rejected".equalsIgnoreCase(status) || Boolean.FALSE.equals(current)) {
                String ref = nullableString(item.get("sourceRef"));
                if (ref != null) invalidated.add(ref);
            }
        }
        return invalidated;
    }

    private static Map<String, Object> automaticSemanticReview(
            P3BCorpus.P3BScenario scenario, String proposedAction, SemanticTrace trace,
            ModelRequestEvidence requests, P3BDecisionQuality.FailureClassification failure,
            String executionResult, int capabilityCalls) {
        Map<String, Object> evidence = inputEvidence(trace, scenario);
        Map<String, Object> decisionStage = stage(trace, "DECISION_INPUT");
        Map<String, Object> input = asMap(decisionStage.get("model_input"));
        if (input.isEmpty()) input = asMap(decisionStage.get("runtime_request"));
        Map<String, Object> snapshot = asMap(input.get("snapshot"));
        Map<String, Object> normalized = asMap(stage(trace, "DECISION_OUTPUT").get("normalized_output"));
        Map<String, Object> payload = asMap(normalized.get("payload"));
        Map<String, Object> argumentAudit = P3BSafeCapabilityEvidence.argumentAudit(
                snapshot, nullableString(payload.get("capabilityId")), asMap(payload.get("arguments")));
        boolean measured = proposedAction != null && requests.requestCount() > 0;
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("status", measured ? "AUTO_REVIEWED" : "UNRESOLVED");
        review.put("reviewer", "p3b-deterministic-evidence-audit.v1");
        review.put("review_scope", "fixture facts, candidate oracle alignment, protocol, source refs, declared parameters, and execution trace");
        review.put("oracle_human_review_status", "PENDING_HUMAN_REVIEW");
        review.put("candidate_oracle_action_match", proposedAction != null
                && scenario.definition().expect().acceptablePrimaryActions().contains(proposedAction));
        review.put("answered_lineage_present", ((Number) evidence.getOrDefault("answered_lineage_entries", 0)).longValue() > 0);
        review.put("unresolved_effective_claim_count", evidence.getOrDefault("unresolved_effective_claims", 0));
        review.put("capability_argument_audit", argumentAudit);
        review.put("clarification_structure_audit", clarificationStructureAudit(
                proposedAction, payload));
        review.put("capability_invocation_count", capabilityCalls);
        review.put("cycle_completed", "completed".equalsIgnoreCase(executionResult));
        review.put("failure_classification", failure.name());
        review.put("source_reference_audit", sourceReferenceAudit(trace));
        review.put("semantic_equivalence", "UNRESOLVED");
        review.put("unsupported_factual_assertions", "UNRESOLVED");
        review.put("basis", List.of(
                "The frozen candidate label was compared without editing it.",
                "The report records structural facts and runtime outcomes; no independent judge model was called."));
        return review;
    }

    private static Map<String, Object> clarificationStructureAudit(
            String actionFamily, Map<String, Object> payload) {
        if (!"REQUEST_USER_INPUT".equals(actionFamily)) {
            return Map.of("status", "NOT_APPLICABLE");
        }
        Object rawQuestion = payload.get("questionText");
        boolean questionPresent = rawQuestion instanceof String question && !question.isBlank();
        return Map.of(
                "status", "DETERMINISTIC_VERIFIED",
                "question_text_present", questionPresent,
                "semantic_intent", "UNRESOLVED");
    }

    private static Path reportPath() {
        String configured = System.getenv("SPEC_AGENT_EVAL_P3B_REPORT_PATH");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir"), "build", "eval-p3b-live",
                        "p3b-live-priority-report.json")
                : Path.of(configured);
    }

    private static String safeCorrelationId(String value) {
        return value == null || value.isBlank() ? "NOT_MEASURED" : sha256(value).substring(0, 20);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static List<String> stringList(Object value) {
        return asList(value).stream().filter(java.util.Objects::nonNull)
                .map(String::valueOf).toList();
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String safeExecutionState(String value) {
        if (value == null || value.isBlank()) return "NOT_MEASURED";
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        for (String state : List.of("completed", "failed", "policy_denied", "awaiting_approval", "cancelled")) {
            if (normalized.equals(state) || normalized.startsWith(state + ":")) return state;
        }
        return "UNKNOWN";
    }

    private int persistedCapabilityInvocations(UUID projectId) {
        if (projectId == null) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capability_invocations WHERE project_id = ?",
                Integer.class, projectId);
        return count == null ? 0 : count;
    }

    private static long count(List<Map<String, Object>> attempts, String key) {
        return attempts.stream().filter(attempt -> Boolean.TRUE.equals(attempt.get(key))).count();
    }

    private static Map<String, Object> stage(SemanticTrace trace, String name) {
        return trace.stages().getOrDefault(name, Map.of());
    }

    private static String traceError(SemanticTrace trace, String field) {
        for (String stageName : List.of("DECISION_OUTPUT", "STATE_UPDATE_OUTPUT")) {
            Object value = stage(trace, stageName).get(field);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** 模型原始选择:优先 DECISION_OUTPUT 的 normalized_output.actionFamily,回退门禁 selected_action。 */
    private static String modelSelectedAction(SemanticTrace trace) {
        Map<String, Object> decision = stage(trace, "DECISION_OUTPUT");
        Object normalized = decision.get("normalized_output");
        if (normalized instanceof Map<?, ?> proposal && proposal.get("actionFamily") != null) {
            return String.valueOf(proposal.get("actionFamily"));
        }
        Object selected = stage(trace, "ACTION_ELIGIBILITY").get("selected_action");
        return selected == null ? null : String.valueOf(selected);
    }

    private record ModelRequestEvidence(int requestCount, int successCount, int failedCount,
                                        List<String> failureCategories,
                                        List<Map<String, Object>> events) {
    }
}
