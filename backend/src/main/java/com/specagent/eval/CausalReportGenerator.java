package com.specagent.eval;

import com.specagent.trace.SemanticTrace;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Deterministic, offline first-fault analysis over semantic trace artifacts.
 * No model, provider, runtime mutation, or evaluation result is invoked here.
 */
public final class CausalReportGenerator {

    public enum FirstFault {
        STATE_UPDATE_FIRST,
        STATE_APPLICATION_FIRST,
        DECISION_INPUT_PROJECTION_FIRST,
        DECISION_FIRST,
        OUTPUT_SCHEMA_FIRST,
        COMPOUND,
        AMBIGUOUS
    }

    private CausalReportGenerator() {
    }

    public static CausalReport generate(List<ObservationEnvelope> observations,
                                        Map<String, ScenarioDefinition> scenarios) {
        List<AttemptFinding> findings = new ArrayList<>();
        for (ObservationEnvelope observation : observations) {
            ScenarioDefinition scenario = scenarios == null ? null
                    : scenarios.get(observation.scenarioId());
            findings.add(analyze(observation, scenario));
        }

        int behavioralFailures = (int) findings.stream()
                .filter(finding -> !finding.infrastructureFailure() && !finding.passed())
                .count();
        int infrastructureFailures = (int) findings.stream()
                .filter(AttemptFinding::infrastructureFailure)
                .count();
        Map<FirstFault, Integer> matrix = new EnumMap<>(FirstFault.class);
        for (FirstFault fault : FirstFault.values()) {
            matrix.put(fault, (int) findings.stream()
                    .filter(finding -> !finding.infrastructureFailure()
                            && !finding.passed()
                            && finding.firstFault() == fault)
                    .count());
        }

        Map<String, Integer> symptoms = new TreeMap<>();
        findings.forEach(finding -> finding.symptoms().forEach(symptom ->
                symptoms.merge(symptom, 1, Integer::sum)));

        Map<String, VariantSummary> variantSummaries = new TreeMap<>();
        findings.stream().collect(Collectors.groupingBy(
                finding -> finding.scenarioId() + "/" + finding.variantId(),
                TreeMap::new, Collectors.toList())).forEach((key, group) ->
                variantSummaries.put(key, VariantSummary.from(group)));

        Map<String, RepetitionSummary> repetitions = new TreeMap<>();
        findings.stream().collect(Collectors.groupingBy(
                finding -> finding.scenarioId() + "/" + finding.variantId(),
                TreeMap::new, Collectors.toList())).forEach((key, group) ->
                repetitions.put(key, RepetitionSummary.from(group)));

        Map<String, String> conclusions = scenarioConclusions(findings, repetitions);
        return new CausalReport(List.copyOf(findings), behavioralFailures,
                infrastructureFailures, Map.copyOf(matrix), Map.copyOf(symptoms),
                Map.copyOf(variantSummaries), Map.copyOf(repetitions),
                Map.copyOf(conclusions));
    }

    private static AttemptFinding analyze(ObservationEnvelope observation,
                                          ScenarioDefinition scenario) {
        SemanticTrace trace = observation.semanticTrace();
        Map<String, Map<String, Object>> stages = trace.stages();
        boolean infrastructure = isInfrastructureFailure(observation);
        List<String> symptoms = symptoms(observation, stages,
                scenario == null ? Set.of() : scenario.expect().acceptablePrimaryActions());
        if (observation.passed()) {
            return finding(observation, false, false, FirstFault.AMBIGUOUS,
                    symptoms, List.of("attempt passed"), fingerprint(stages, "DECISION_INPUT"),
                    fingerprint(stages, "STATE_UPDATE_OUTPUT"), fingerprint(stages, "DECISION_OUTPUT"));
        }
        if (infrastructure) {
            return finding(observation, false, true, FirstFault.AMBIGUOUS,
                    symptoms, List.of("provider/runtime failure; excluded from behavioral matrix"),
                    fingerprint(stages, "DECISION_INPUT"), fingerprint(stages, "STATE_UPDATE_OUTPUT"),
                    fingerprint(stages, "DECISION_OUTPUT"));
        }

        FirstFault capturedFailure = capturedFailure(stages);
        if (capturedFailure != null) {
            return finding(observation, false, false, capturedFailure, symptoms,
                    List.of("captured failure stage: " + capturedFailure),
                    fingerprint(stages, "DECISION_INPUT"),
                    fingerprint(stages, "STATE_UPDATE_OUTPUT"),
                    fingerprint(stages, "DECISION_OUTPUT"));
        }

        List<String> evidence = new ArrayList<>();
        FirstFault fault = classify(observation, scenario, stages, evidence);
        return finding(observation, false, false, fault, symptoms, evidence,
                fingerprint(stages, "DECISION_INPUT"),
                fingerprint(stages, "STATE_UPDATE_OUTPUT"),
                fingerprint(stages, "DECISION_OUTPUT"));
    }

    private static AttemptFinding finding(ObservationEnvelope observation,
                                          boolean passed, boolean infrastructure,
                                          FirstFault firstFault, List<String> symptoms,
                                          List<String> evidence,
                                          String decisionInputFingerprint,
                                          String stateOutputFingerprint,
                                          String decisionOutputFingerprint) {
        return new AttemptFinding(observation.scenarioId(), observation.variantId(),
                observation.repetition(), observation.actualPrimaryAction(),
                passed || observation.passed(), infrastructure, firstFault,
                List.copyOf(symptoms), List.copyOf(evidence),
                decisionInputFingerprint, stateOutputFingerprint,
                decisionOutputFingerprint);
    }

    private static FirstFault classify(ObservationEnvelope observation,
                                       ScenarioDefinition scenario,
                                       Map<String, Map<String, Object>> stages,
                                       List<String> evidence) {
        if (scenario == null || !complete(stages)) {
            evidence.add("semantic stages incomplete");
            return FirstFault.AMBIGUOUS;
        }

        List<Map<String, Object>> updateClaims = claims(stages.get("STATE_UPDATE_OUTPUT"),
                "normalized_output", "claims");
        List<Map<String, Object>> postClaims = claims(stages.get("POST_STATE_UPDATE_STATE"),
                "effective_claims");
        List<Map<String, Object>> decisionClaims = decisionInputClaims(stages);
        boolean unresolvedConflict = hasClaim(updateClaims, "conflict", "unresolved");
        boolean postUnresolvedConflict = hasClaim(postClaims, "conflict", "unresolved");
        String scenarioId = observation.scenarioId();

        // These two scenario-family checks encode the semantic intent of the
        // existing frozen contracts, not a prompt exception.
        if ("E07".equals(scenarioId) && !unresolvedConflict) {
            evidence.add("STATE_UPDATE_OUTPUT lacks conflict/unresolved claim");
            return FirstFault.STATE_UPDATE_FIRST;
        }
        if ("E07-resolved".equals(scenarioId) && unresolvedConflict) {
            evidence.add("STATE_UPDATE_OUTPUT recreated an unresolved conflict in resolved case");
            return FirstFault.STATE_UPDATE_FIRST;
        }

        for (Map<String, Object> claim : updateClaims) {
            if (!containsClaim(postClaims, claim)) {
                evidence.add("STATE_UPDATE_OUTPUT claim is absent from POST_STATE_UPDATE_STATE: "
                        + claimSummary(claim));
                return FirstFault.STATE_APPLICATION_FIRST;
            }
        }

        List<Map<String, Object>> requiredForDecision = updateClaims;
        if ("E07".equals(scenarioId) && postUnresolvedConflict
                && !hasClaim(decisionClaims, "conflict", "unresolved")) {
            requiredForDecision = List.of(Map.of(
                    "kind", "conflict", "status", "unresolved", "text", "conflict"));
        }
        for (Map<String, Object> claim : requiredForDecision) {
            if (!containsClaim(decisionClaims, claim)) {
                evidence.add("POST_STATE_UPDATE_STATE claim is absent from DECISION_INPUT: "
                        + claimSummary(claim));
                return FirstFault.DECISION_INPUT_PROJECTION_FIRST;
            }
        }

        String actual = observation.actualPrimaryAction();
        boolean actionWrong = actual == null
                || !scenario.expect().acceptablePrimaryActions().contains(actual)
                || scenario.expect().forbiddenActions().contains(actual);
        if ("E07-resolved".equals(scenarioId)
                && ("REQUEST_USER_INPUT".equals(actual) || "CREATE_NODE".equals(actual))) {
            actionWrong = true;
            evidence.add("resolved case still asked or created a node");
        }
        if ("E17".equals(scenarioId)
                && !Boolean.TRUE.equals(value(stages.get("POLICY_DECISION"),
                "requires_confirmation"))) {
            evidence.add("high-risk capability input reached policy without confirmation requirement");
            return FirstFault.DECISION_FIRST;
        }
        if (actionWrong) {
            evidence.add("DECISION_INPUT carries the post-state semantics but DECISION_OUTPUT action is "
                    + actual);
            return FirstFault.DECISION_FIRST;
        }

        boolean propertyFailure = observation.violations().stream()
                .anyMatch(violation -> violation.failureClass() == FailureClass.REQUIRED_PROPERTY_MISSING);
        if (propertyFailure) {
            evidence.add("action family is acceptable; only required property/schema checks failed");
            return FirstFault.OUTPUT_SCHEMA_FIRST;
        }
        evidence.add("no single earlier semantic fault proven by trace");
        return FirstFault.AMBIGUOUS;
    }

    private static boolean complete(Map<String, Map<String, Object>> stages) {
        return stages.keySet().containsAll(Set.of(
                "STATE_UPDATE_INPUT", "STATE_UPDATE_OUTPUT", "POST_STATE_UPDATE_STATE",
                "DECISION_INPUT", "DECISION_OUTPUT"));
    }

    private static FirstFault capturedFailure(Map<String, Map<String, Object>> stages) {
        List<FirstFault> failures = new ArrayList<>();
        if (hasFailure(stages, "STATE_UPDATE_OUTPUT")) {
            failures.add(FirstFault.STATE_UPDATE_FIRST);
        }
        if (hasFailure(stages, "STATE_APPLICATION")) {
            failures.add(FirstFault.STATE_APPLICATION_FIRST);
        }
        if (hasFailure(stages, "DECISION_INPUT_PROJECTION")) {
            failures.add(FirstFault.DECISION_INPUT_PROJECTION_FIRST);
        }
        if (hasFailure(stages, "DECISION_OUTPUT")) {
            failures.add(FirstFault.OUTPUT_SCHEMA_FIRST);
        }
        return failures.size() > 1 ? FirstFault.COMPOUND
                : failures.isEmpty() ? null : failures.get(0);
    }

    private static boolean hasFailure(Map<String, Map<String, Object>> stages,
                                      String stage) {
        return stages.containsKey(stage) && stages.get(stage).containsKey("error_type");
    }

    private static boolean isInfrastructureFailure(ObservationEnvelope observation) {
        if (observation.executionResult() != null
                && observation.executionResult().startsWith("failed:")) {
            return true;
        }
        return observation.violations().stream().anyMatch(violation ->
                violation.failureClass() == FailureClass.PROVIDER_FAILURE);
    }

    private static List<String> symptoms(ObservationEnvelope observation,
                                         Map<String, Map<String, Object>> stages,
                                         Set<String> acceptable) {
        Set<String> result = new LinkedHashSet<>();
        String action = observation.actualPrimaryAction();
        if ("CREATE_NODE".equals(action) && acceptable.contains("REQUEST_USER_INPUT")) {
            result.add("CN instead of RUI");
        }
        if ("E07-resolved".equals(observation.scenarioId())
                && ("REQUEST_USER_INPUT".equals(action) || "CREATE_NODE".equals(action))) {
            result.add("unnecessary RUI/node creation");
        }
        if ("E22-wait".equals(observation.scenarioId()) && !"WAIT".equals(action)) {
            result.add("WAIT mishandling");
        }
        List<Map<String, Object>> update = claims(stages.get("STATE_UPDATE_OUTPUT"),
                "normalized_output", "claims");
        List<Map<String, Object>> post = claims(stages.get("POST_STATE_UPDATE_STATE"),
                "effective_claims");
        List<Map<String, Object>> decision = decisionInputClaims(stages);
        if ("E07".equals(observation.scenarioId())
                && (!hasClaim(update, "conflict", "unresolved")
                || !hasClaim(post, "conflict", "unresolved")
                || !hasClaim(decision, "conflict", "unresolved"))) {
            result.add("conflict missing");
        }
        if ("E07-resolved".equals(observation.scenarioId())
                && (hasClaim(update, "conflict", "unresolved")
                || hasClaim(post, "conflict", "unresolved")
                || hasClaim(decision, "conflict", "unresolved"))) {
            result.add("stale/resolved conflict");
        }
        if ("E17".equals(observation.scenarioId())
                && !Boolean.TRUE.equals(value(stages.get("POLICY_DECISION"),
                "requires_confirmation"))) {
            result.add("confirmation state missing");
        }
        if ("INVOKE_CAPABILITY".equals(action)
                && acceptable.stream().noneMatch("INVOKE_CAPABILITY"::equals)) {
            result.add("forbidden IC");
        }
        if ("REQUEST_USER_INPUT".equals(action)
                && acceptable.stream().noneMatch("REQUEST_USER_INPUT"::equals)) {
            result.add("forbidden RTU");
        }
        observation.violations().forEach(violation -> {
            if (violation.failureClass() == FailureClass.UNEXPECTED_STATE_DELTA) {
                result.add("unexpected state delta");
            }
            if (violation.failureClass() == FailureClass.REQUIRED_PROPERTY_MISSING) {
                result.add("schema/property missing");
            }
        });
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> claims(Map<String, Object> stage,
                                                    String... path) {
        Object current = stage;
        for (String key : path) {
            current = map(current).get(key);
        }
        if (!(current instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(CausalReportGenerator::map).toList();
    }

    private static List<Map<String, Object>> decisionInputClaims(Map<String, Map<String, Object>> stages) {
        Map<String, Object> input = stages.get("DECISION_INPUT");
        Map<String, Object> modelInput = map(input == null ? null : input.get("model_input"));
        Map<String, Object> request = modelInput.isEmpty()
                ? map(input == null ? null : input.get("runtime_request")) : modelInput;
        Map<String, Object> snapshot = map(request.get("snapshot"));
        Object claims = snapshot.get("effectiveClaims");
        if (!(claims instanceof List<?>)) {
            claims = snapshot.get("effective_claims");
        }
        if (!(claims instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(CausalReportGenerator::map).toList();
    }

    private static boolean hasClaim(List<Map<String, Object>> claims,
                                    String kind, String status) {
        return claims.stream().anyMatch(claim -> kind.equals(string(claim, "kind"))
                && status.equals(string(claim, "status")));
    }

    private static boolean containsClaim(List<Map<String, Object>> claims,
                                         Map<String, Object> expected) {
        return claims.stream().anyMatch(actual ->
                string(expected, "kind").equals(string(actual, "kind"))
                        && string(expected, "status").equals(string(actual, "status"))
                        && (string(expected, "text").isBlank()
                        || string(actual, "text").equals(string(expected, "text"))));
    }

    private static String claimSummary(Map<String, Object> claim) {
        return string(claim, "kind") + "/" + string(claim, "status")
                + ":" + string(claim, "text");
    }

    private static String fingerprint(Map<String, Map<String, Object>> stages, String stage) {
        return string(stages.get(stage), "semantic_fingerprint");
    }

    private static Object value(Map<String, Object> map, String key) {
        return map == null ? null : map.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, String> scenarioConclusions(
            List<AttemptFinding> findings,
            Map<String, RepetitionSummary> repetitions) {
        Map<String, String> result = new TreeMap<>();
        for (String scenario : List.of("E01", "E07", "E07-resolved", "E17",
                "E22-wait", "E19", "E10", "E25")) {
            List<AttemptFinding> group = findings.stream()
                    .filter(finding -> scenario.equals(finding.scenarioId()))
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            String conclusion = switch (scenario) {
                case "E01" -> repetitionConclusion(group,
                        "E01 CN/RUI switching is attributable to DECISION output when semantic fingerprints stay equivalent.");
                case "E07" -> "E07 unresolved conflict evidence is checked through STATE_UPDATE, post-state, and DECISION projection before action classification.";
                case "E07-resolved" -> "E07-resolved distinguishes stale conflict recreation from a decision that ignores a correctly resolved state.";
                case "E17" -> "E17 confirmation is traced at the policy boundary; missing confirmation is not conflated with action wording.";
                case "E22-wait" -> "E22-wait compares the DECISION_INPUT prerequisite state with the chosen action.";
                case "E19" -> repetitionConclusion(group,
                        "E19 stable CREATE_NODE overreach is DECISION evidence if post-state and DECISION_INPUT remain complete.");
                case "E10" -> "E10 checks whether resource grounding changes the semantic input or only the selected action.";
                case "E25" -> "E25 checks frozen/stale context evidence without treating provider failures as behavioral faults.";
                default -> "No conclusion.";
            };
            result.put(scenario, conclusion);
        }
        return result;
    }

    private static String repetitionConclusion(List<AttemptFinding> findings,
                                               String fallback) {
        Set<String> state = findings.stream().map(AttemptFinding::stateOutputFingerprint)
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.toSet());
        Set<String> input = findings.stream().map(AttemptFinding::decisionInputFingerprint)
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.toSet());
        Set<String> output = findings.stream().map(AttemptFinding::decisionOutputFingerprint)
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.toSet());
        if (state.size() > 1) {
            return "STATE_UPDATE semantic output differs across repetitions; variance originates upstream. " + fallback;
        }
        if (input.size() > 1) {
            return "STATE_UPDATE output is stable but DECISION_INPUT differs across repetitions; inspect runtime/projection. " + fallback;
        }
        if (output.size() > 1) {
            return "STATE_UPDATE and DECISION_INPUT are semantically stable but DECISION output differs; strong decision nondeterminism/underspecification evidence. " + fallback;
        }
        return fallback;
    }

    public record AttemptFinding(String scenarioId, String variantId, int repetition,
                                 String actualAction, boolean passed,
                                 boolean infrastructureFailure, FirstFault firstFault,
                                 List<String> symptoms, List<String> evidence,
                                 String decisionInputFingerprint,
                                 String stateOutputFingerprint,
                                 String decisionOutputFingerprint) {
    }

    public record VariantSummary(int attempts, int passed, int behavioralFailures,
                                 int infrastructureFailures,
                                 Map<FirstFault, Integer> firstFaults) {
        private static VariantSummary from(List<AttemptFinding> group) {
            Map<FirstFault, Integer> faults = new EnumMap<>(FirstFault.class);
            group.stream().filter(finding -> !finding.passed() && !finding.infrastructureFailure())
                    .forEach(finding -> faults.merge(finding.firstFault(), 1, Integer::sum));
            return new VariantSummary(group.size(),
                    (int) group.stream().filter(AttemptFinding::passed).count(),
                    (int) group.stream().filter(finding -> !finding.passed()
                            && !finding.infrastructureFailure()).count(),
                    (int) group.stream().filter(AttemptFinding::infrastructureFailure).count(),
                    Map.copyOf(faults));
        }
    }

    public record RepetitionSummary(int attempts, Set<String> stateOutputFingerprints,
                                    Set<String> decisionInputFingerprints,
                                    Set<String> decisionOutputFingerprints,
                                    Set<String> actions, String interpretation) {
        private static RepetitionSummary from(List<AttemptFinding> group) {
            Set<String> state = fingerprints(group, AttemptFinding::stateOutputFingerprint);
            Set<String> input = fingerprints(group, AttemptFinding::decisionInputFingerprint);
            Set<String> output = fingerprints(group, AttemptFinding::decisionOutputFingerprint);
            Set<String> actions = group.stream().map(AttemptFinding::actualAction)
                    .filter(value -> value != null && !value.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
            String interpretation = state.size() > 1
                    ? "variance originates upstream"
                    : input.size() > 1
                    ? "runtime/projection variation"
                    : output.size() > 1 || actions.size() > 1
                    ? "DECISION nondeterminism or underspecified policy"
                    : "semantic/action outcome stable";
            return new RepetitionSummary(group.size(), Set.copyOf(state), Set.copyOf(input),
                    Set.copyOf(output), Set.copyOf(actions), interpretation);
        }

        private static Set<String> fingerprints(List<AttemptFinding> group,
                                                java.util.function.Function<AttemptFinding, String> getter) {
            return group.stream().map(getter).filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    public record CausalReport(List<AttemptFinding> attempts,
                               int behavioralFailures,
                               int infrastructureFailures,
                               Map<FirstFault, Integer> firstFaultMatrix,
                               Map<String, Integer> symptomMatrix,
                               Map<String, VariantSummary> variantSummaries,
                               Map<String, RepetitionSummary> repetitionSummaries,
                               Map<String, String> scenarioConclusions) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("schema_version", "causal-report.v1");
            map.put("behavioral_failures", behavioralFailures);
            map.put("infrastructure_failures", infrastructureFailures);
            List<Map<String, Object>> matrix = new ArrayList<>();
            for (FirstFault fault : FirstFault.values()) {
                int count = firstFaultMatrix.getOrDefault(fault, 0);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("first_fault", fault.name());
                row.put("attempts", count);
                row.put("percent_behavioral_failures", behavioralFailures == 0
                        ? 0.0 : (double) count * 100.0 / behavioralFailures);
                matrix.add(row);
            }
            map.put("first_fault_matrix", matrix);
            map.put("symptom_matrix", symptomMatrix);
            map.put("variant_summaries", variantSummaries.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            entry -> variantMap(entry.getValue()),
                            (left, right) -> left, LinkedHashMap::new)));
            map.put("repetition_summaries", repetitionSummaries.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            entry -> repetitionMap(entry.getValue()),
                            (left, right) -> left, LinkedHashMap::new)));
            map.put("scenario_conclusions", scenarioConclusions);
            map.put("attempts", attempts.stream().map(CausalReport::attemptMap).toList());
            return map;
        }

        private static Map<String, Object> attemptMap(AttemptFinding attempt) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scenario_id", attempt.scenarioId());
            map.put("variant_id", attempt.variantId());
            map.put("repetition", attempt.repetition());
            map.put("actual_action", attempt.actualAction());
            map.put("passed", attempt.passed());
            map.put("infrastructure_failure", attempt.infrastructureFailure());
            map.put("first_fault", attempt.firstFault().name());
            map.put("symptoms", attempt.symptoms());
            map.put("evidence", attempt.evidence());
            map.put("decision_input_fingerprint", attempt.decisionInputFingerprint());
            map.put("state_output_fingerprint", attempt.stateOutputFingerprint());
            map.put("decision_output_fingerprint", attempt.decisionOutputFingerprint());
            return map;
        }

        private static Map<String, Object> variantMap(VariantSummary summary) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("attempts", summary.attempts());
            map.put("passed", summary.passed());
            map.put("behavioral_failures", summary.behavioralFailures());
            map.put("infrastructure_failures", summary.infrastructureFailures());
            Map<String, Integer> faults = new TreeMap<>();
            summary.firstFaults().forEach((key, value) -> faults.put(key.name(), value));
            map.put("first_faults", faults);
            return map;
        }

        private static Map<String, Object> repetitionMap(RepetitionSummary summary) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("attempts", summary.attempts());
            map.put("state_output_fingerprints", summary.stateOutputFingerprints().stream().sorted().toList());
            map.put("decision_input_fingerprints", summary.decisionInputFingerprints().stream().sorted().toList());
            map.put("decision_output_fingerprints", summary.decisionOutputFingerprints().stream().sorted().toList());
            map.put("actions", summary.actions().stream().sorted().toList());
            map.put("interpretation", summary.interpretation());
            return map;
        }

        public String toText() {
            StringBuilder text = new StringBuilder();
            text.append("causal report: behavioral_failures=").append(behavioralFailures)
                    .append(" infrastructure_failures=").append(infrastructureFailures).append('\n');
            text.append("first-fault matrix:\n");
            for (FirstFault fault : FirstFault.values()) {
                int count = firstFaultMatrix.getOrDefault(fault, 0);
                double percent = behavioralFailures == 0 ? 0.0
                        : (double) count * 100.0 / behavioralFailures;
                text.append(fault.name()).append("=").append(count)
                        .append(" (").append(String.format("%.1f", percent)).append("%)\n");
            }
            text.append("symptoms=").append(symptomMatrix).append('\n');
            scenarioConclusions.forEach((key, value) ->
                    text.append(key).append(" -> ").append(value).append('\n'));
            text.append("attempt causal chains:\n");
            attempts.stream().filter(attempt -> !attempt.passed()).forEach(attempt ->
                    text.append(attempt.scenarioId()).append('/').append(attempt.variantId())
                            .append(" rep").append(attempt.repetition())
                            .append(" first_fault=").append(attempt.infrastructureFailure()
                                    ? "INFRASTRUCTURE" : attempt.firstFault())
                            .append(" action=").append(attempt.actualAction())
                            .append(" evidence=").append(attempt.evidence()).append('\n'));
            return text.toString();
        }
    }
}
