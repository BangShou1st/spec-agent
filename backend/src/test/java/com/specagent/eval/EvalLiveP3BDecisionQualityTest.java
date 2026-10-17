package com.specagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.trace.SemanticTrace;
import com.specagent.model.provider.OpenCodeModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs priority P3-B cases through the real Host -> Brain broker -> OpenCode chain. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18082",
                "spec.agent.brain.engine=remote-python",
                "spec.agent.brain.base-url=${SPEC_AGENT_EVAL_BRAIN_BASE_URL:http://localhost:8100}",
                "spec.agent.brain.internal-secret=${SPEC_AGENT_BRAIN_INTERNAL_SECRET:dev-internal-secret}",
                "spec.agent.model.inference=opencode",
                "spec.agent.model.runtime-settings-source=external-environment",
                "spec.agent.model.external.api-key=${SPEC_AGENT_EVAL_OPENCODE_KEY:}",
                "spec.agent.model.external.selected-model=${SPEC_AGENT_EVAL_OPENCODE_MODEL:}",
                "spec.agent.model.opencode.base-url=https://opencode.ai/inference/openai/v1",
                "spec.agent.semantic-trace.enabled=true"
        })
class EvalLiveP3BDecisionQualityTest extends EvalLiveHarnessBase {

    private static final String EXPECTED_MODEL = "deepseek-v4.1-flash-free";
    private static final Set<String> PRIORITY_IDS = Set.of(
            "P3B-C03", "P3B-C07", "P3B-C13", "P3B-C15");
    private static final String BRAIN_HEALTH = System.getenv().getOrDefault(
            "SPEC_AGENT_EVAL_BRAIN_BASE_URL", "http://localhost:8100") + "/health";

    @Autowired
    private OpenCodeModelCatalog modelCatalog;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void priorityScenariosRecordRealModelDecisionEvidence() throws Exception {
        LiveChainEvidence before = requireLiveBrain(BRAIN_HEALTH);
        assertThat(before.selectedModel()).isEqualTo(EXPECTED_MODEL);
        assertThat(modelCatalog.chatExecutable(EXPECTED_MODEL)).isTrue();
        assertThat(modelCatalog.isFreeModel(EXPECTED_MODEL)).isTrue();

        List<Map<String, Object>> attemptReports = new ArrayList<>();
        List<P3BDecisionQuality.DecisionObservation> qualityObservations = new ArrayList<>();
        Map<String, P3BCorpus.P3BOracle> oracles = new LinkedHashMap<>();
        boolean rateLimited = false;
        boolean stoppedAfterProviderFailure = false;
        boolean stoppedAfterUnclassifiedFailure = false;
        outer:
        for (P3BCorpus.P3BScenario scenario : P3BCorpus.all()) {
            if (!PRIORITY_IDS.contains(scenario.scenarioId())) {
                continue;
            }
            oracles.put(scenario.scenarioId(), scenario.oracle());
            for (VariantSpec variant : scenario.definition().variants()) {
                long startedAt = System.nanoTime();
                ObservationEnvelope observation = runLiveScenario(
                        scenario.definition(), variant, 1).get(0);
                long measuredDurationMs = (System.nanoTime() - startedAt) / 1_000_000;
                UUID projectId = scenarioRunner.lastProjectId();
                // Agent run events are keyed by the observation's attempt id.
                // runId is an optional reporting field and can be null here.
                ModelRequestEvidence requestEvidence = modelRequestEvidence(observation.attemptId());
                int capabilityCalls = persistedCapabilityInvocations(projectId);
                P3BDecisionQuality.FailureClassification failure =
                        P3BDecisionQuality.classifyFailure(
                                observation.executionResult(),
                                requestEvidence.failureCategories().stream().findFirst().orElse(null),
                                stage(observation.semanticTrace(), "ACTION_ELIGIBILITY"),
                                traceError(observation.semanticTrace(), "error_type"),
                                traceError(observation.semanticTrace(), "error_summary"));
                Map<String, Object> eligibility = stage(
                        observation.semanticTrace(), "ACTION_ELIGIBILITY");
                String actualAction = observation.actualPrimaryAction();
                boolean actionAcceptable = actualAction != null && scenario.definition().expect()
                        .acceptablePrimaryActions().contains(actualAction);
                boolean unnecessaryClarification = "P3B-C03".equals(scenario.scenarioId())
                        && "REQUEST_USER_INPUT".equals(actualAction);
                boolean unnecessaryCapability = "P3B-C07".equals(scenario.scenarioId())
                        && ("INVOKE_CAPABILITY".equals(actualAction) || capabilityCalls > 0);
                boolean semanticErrorSignal = actualAction != null && (!actionAcceptable
                        || unnecessaryClarification || unnecessaryCapability);

                qualityObservations.add(new P3BDecisionQuality.DecisionObservation(
                        scenario.scenarioId(), variant.variantId(), actualAction,
                        actionAcceptable,
                        P3BDecisionQuality.gateOutcome(eligibility),
                        P3BDecisionQuality.gateReasonCodes(eligibility),
                        P3BDecisionQuality.payloadValidity(actualAction,
                                observation.executionResult()),
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
                attempt.put("attempt_id", observation.attemptId());
                attempt.put("run_id", observation.attemptId());
                attempt.put("reported_run_id", observation.runId());
                attempt.put("action", actualAction);
                attempt.put("action_measured", actualAction != null && !actualAction.isBlank());
                attempt.put("acceptable_action", actionAcceptable);
                attempt.put("payload_validity", P3BDecisionQuality.payloadValidity(
                        actualAction, observation.executionResult()).name());
                attempt.put("eligibility_veto", P3BDecisionQuality.gateOutcome(eligibility).name());
                attempt.put("eligibility_reason_codes",
                        P3BDecisionQuality.gateReasonCodes(eligibility));
                attempt.put("execution_result", observation.executionResult());
                attempt.put("failure_classification", failure.name());
                attempt.put("model_request_count", requestEvidence.requestCount());
                attempt.put("successful_model_requests", requestEvidence.successCount());
                attempt.put("failed_model_requests", requestEvidence.failedCount());
                attempt.put("provider_failure_categories", requestEvidence.failureCategories());
                attempt.put("capability_calls", capabilityCalls);
                attempt.put("repeated_clarification", unnecessaryClarification);
                attempt.put("unnecessary_capability_call", unnecessaryCapability);
                attempt.put("action_acceptable_set_error_signal",
                        actualAction != null && !actionAcceptable);
                attempt.put("unresolved_semantic_error_signal", semanticErrorSignal);
                attempt.put("manual_semantic_review_required",
                        actualAction != null
                                && Set.of("P3B-C13", "P3B-C15").contains(scenario.scenarioId()));
                attempt.put("duration_ms", observation.latencyMs() == null
                        ? measuredDurationMs : observation.latencyMs());
                attempt.put("measured_duration_ms", measuredDurationMs);
                attempt.put("stage_latency_ms", observation.stageLatencyMs());
                attempt.put("decision_output", stage(
                        observation.semanticTrace(), "DECISION_OUTPUT").get("normalized_output"));
                attemptReports.add(attempt);

                if (failure == P3BDecisionQuality.FailureClassification.RATE_LIMITED) {
                    rateLimited = true;
                    break outer;
                }
                if (failure.infrastructureFailure()) {
                    stoppedAfterProviderFailure = true;
                    break outer;
                }
                if (requestEvidence.failedCount() > 0
                        || failure == P3BDecisionQuality.FailureClassification.UNKNOWN_FAILURE) {
                    stoppedAfterUnclassifiedFailure = true;
                    break outer;
                }
            }
        }

        P3BDecisionQuality.Summary summary = P3BDecisionQuality.summarize(
                qualityObservations, oracles);
        LiveBrainHealth after = readLiveBrainHealth(BRAIN_HEALTH);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", "p3b-live-priority.v1");
        report.put("suite", "EvalLiveP3BDecisionQualityTest");
        report.put("model_id", before.selectedModel());
        report.put("model_protocol", modelCatalog.protocolOf(before.selectedModel()).name());
        report.put("model_chat_catalog_qualified",
                modelCatalog.chatExecutable(before.selectedModel()));
        report.put("model_listed_as_free", modelCatalog.isFreeModel(before.selectedModel()));
        report.put("endpoint", before.endpoint());
        report.put("brain_model_mode", before.pythonBefore().modelMode());
        report.put("scenario_count", PRIORITY_IDS.size());
        report.put("attempt_count", attemptReports.size());
        report.put("attempts_are_independent_scenarios", false);
        report.put("labels", "AI candidate labels; pending human review");
        report.put("stopped_after_rate_limit", rateLimited);
        report.put("stopped_after_provider_failure", stoppedAfterProviderFailure);
        report.put("stopped_after_unclassified_failure", stoppedAfterUnclassifiedFailure);
        report.put("total_model_requests", attemptReports.stream()
                .mapToInt(attempt -> ((Number) attempt.get("model_request_count")).intValue()).sum());
        report.put("action_acceptable_set_accuracy", summary.actionAcceptableSetAccuracy());
        report.put("action_measured_count", summary.actionMeasuredCount());
        report.put("action_acceptable_set_denominator", summary.actionMeasuredCount());
        report.put("repeated_clarification_count", count(attemptReports, "repeated_clarification"));
        report.put("repeated_clarification_measured_count", attemptReports.stream()
                .filter(attempt -> "P3B-C03".equals(attempt.get("scenario_id")))
                .filter(attempt -> Boolean.TRUE.equals(attempt.get("action_measured"))).count());
        report.put("unnecessary_capability_call_count",
                count(attemptReports, "unnecessary_capability_call"));
        report.put("unnecessary_capability_call_measured_count", attemptReports.stream()
                .filter(attempt -> "P3B-C07".equals(attempt.get("scenario_id")))
                .filter(attempt -> Boolean.TRUE.equals(attempt.get("action_measured"))).count());
        report.put("capability_call_count", summary.totalCapabilityCalls());
        report.put("payload_validity", summary.payloadValidity());
        report.put("payload_validity_measured", summary.payloadValidityMeasuredCount());
        report.put("payload_validity_invalid_count",
                attemptReports.stream().filter(attempt -> "INVALID".equals(
                        attempt.get("payload_validity"))).count());
        report.put("eligibility_veto_count",
                attemptReports.stream().filter(attempt -> "VETOED".equals(
                        attempt.get("eligibility_veto"))).count());
        long eligibilityUnknownCount = attemptReports.stream()
                .filter(attempt -> "UNKNOWN".equals(attempt.get("eligibility_veto"))).count();
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
        report.put("python_state_update_delta",
                after.stateUpdates() - before.pythonBefore().stateUpdates());
        report.put("python_decision_delta",
                after.decisions() - before.pythonBefore().decisions());

        Path output = Path.of(System.getProperty("user.dir"), "build", "eval-p3b-live",
                "p3b-live-priority-report.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("P3-B live priority report: " + output.toAbsolutePath());
        System.out.println("P3-B live model=" + before.selectedModel()
                + " attempts=" + attemptReports.size()
                + " requests=" + report.get("total_model_requests")
                + " action_accuracy=" + summary.actionAcceptableSetAccuracy()
                + " failures=" + summary.failureClassificationBreakdown());
    }

    private ModelRequestEvidence modelRequestEvidence(String runId) {
        if (runId == null || runId.isBlank()) {
            return new ModelRequestEvidence(0, 0, 0, List.of());
        }
        List<String> categories = new ArrayList<>();
        int[] counts = new int[3];
        jdbcTemplate.query(
                "SELECT event_type, payload ->> 'gatewayCategory' FROM agent_run_events "
                        + "WHERE run_id = ? AND event_type IN ('MODEL_INFERENCE', "
                        + "'MODEL_INFERENCE_FAILED') ORDER BY sequence",
                resultSet -> {
                    while (resultSet.next()) {
                        counts[0]++;
                        if ("MODEL_INFERENCE".equals(resultSet.getString(1))) {
                            counts[1]++;
                        } else {
                            counts[2]++;
                            String category = resultSet.getString(2);
                            if (category != null && !categories.contains(category)) {
                                categories.add(category);
                            }
                        }
                    }
                    return null;
                }, UUID.fromString(runId));
        return new ModelRequestEvidence(counts[0], counts[1], counts[2], categories);
    }

    private int persistedCapabilityInvocations(UUID projectId) {
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

    private record ModelRequestEvidence(int requestCount, int successCount, int failedCount,
                                        List<String> failureCategories) {
    }
}
