package com.specagent.eval;

import com.specagent.trace.SemanticTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Machine-readable artifact writer: one JSONL line per attempt plus
 * summary.json / summary.txt aggregates. Ships its own minimal JSON
 * renderer so the harness adds no new framework dependency.
 */
public final class EvalArtifactWriter {

    private EvalArtifactWriter() {
    }

    public static String toJsonl(ObservationEnvelope observation) {
        return toJson(observation.toMap());
    }

    public static ObservationEnvelope fromJsonl(String line) {
        Map<String, Object> map = parseObject(line.trim());
        String scenarioId = str(map.get("scenario_id"));
        String variantId = str(map.get("variant_id"));
        String scenarioHash = str(map.get("scenario_hash"));
        EvaluationProfile profile = map.get("evaluation_profile") == null
                ? EvaluationProfile.B_FAST
                : EvaluationProfile.valueOf(str(map.get("evaluation_profile")));
        ObservationEnvelope.Builder builder =
                ObservationEnvelope.builder(scenarioId, variantId, scenarioHash, profile)
                        .actualPrimaryAction(str(map.get("actual_primary_action")))
                        .executionResult(str(map.get("execution_result")))
                        .stateDelta(intMap(map.get("state_delta_summary")))
                        .violations(violations(map.get("violations")))
                        .seed(toLong(map.get("seed")));
        if (map.get("run_id") != null) {
            builder.runId(str(map.get("run_id")));
        }
        if (map.get("git_sha") != null) {
            builder.gitSha(str(map.get("git_sha")));
        }
        if (map.get("attempt_id") != null) {
            try {
                builder.attemptId(java.util.UUID.fromString(str(map.get("attempt_id"))));
            } catch (IllegalArgumentException ignored) {
                // Keep the artifact readable; malformed diagnostic identity
                // is not allowed to make unrelated fields disappear.
            }
        }
        if (map.get("repetition") instanceof Number number) {
            builder.repetition(number.intValue());
        }
        builder.diagnosticMetadata(str(map.get("baseline_reference_commit")),
                str(map.get("instrumentation_commit")));
        if (map.get("semantic_trace") instanceof Map<?, ?> trace) {
            builder.semanticTrace(SemanticTrace.fromMap(stringMap(trace)));
        }
        Object cost = map.get("cost");
        if (cost != null) {
            builder.cost(str(cost));
        }
        builder.callBudget(CallBudgetTracker.of(
                toInt(map.get("production_model_calls")),
                toInt(map.get("provider_retries")),
                toInt(map.get("judge_model_calls")),
                toInt(map.get("capability_calls"))));
        Object totalLatency = map.get("total_latency");
        if (totalLatency instanceof Number number) {
            builder.latencyMs(number.longValue());
        }
        return builder.build();
    }

    private static Map<String, Object> stringMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Violation> violations(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Violation> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = item instanceof Map<?, ?> raw ? stringMap(raw) : Map.of();
            String failure = str(map.get("failure_class"));
            if (failure == null) {
                continue;
            }
            try {
                result.add(new Violation(FailureClass.valueOf(failure),
                        str(map.get("detail"))));
            } catch (IllegalArgumentException ignored) {
                // Unknown diagnostic taxonomy stays out of the typed view.
            }
        }
        return result;
    }

    public static String summaryJson(EvalSummary summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total_attempts", summary.totalAttempts());
        map.put("passed", summary.passed());
        map.put("failed", summary.failed());
        map.put("layer_a_pass_rate", summary.layerAPassRate());
        map.put("layer_b_fast_pass_rate", summary.layerBFastPassRate());
        Map<String, Integer> failures = new LinkedHashMap<>();
        summary.failureCounts().forEach((key, value) -> failures.put(key.name(), value));
        map.put("failure_counts", failures);
        map.put("primary_action_distribution", new TreeMap<>(summary.primaryActionDistribution()));
        map.put("unexpected_state_deltas", summary.unexpectedStateDeltas());
        map.put("call_budget_violations", summary.callBudgetViolations());
        map.put("production_model_calls", summary.productionModelCalls());
        map.put("provider_retries", summary.providerRetries());
        map.put("judge_model_calls", summary.judgeModelCalls());
        map.put("capability_calls", summary.capabilityCalls());
        map.put("scenario_results", summary.scenarioResults());
        return toJson(map);
    }

    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + escape(text) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder rendered = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    rendered.append(",");
                }
                first = false;
                rendered.append("\"").append(escape(String.valueOf(entry.getKey()))).append("\":");
                rendered.append(toJson(entry.getValue()));
            }
            return rendered.append("}").toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder rendered = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    rendered.append(",");
                }
                first = false;
                rendered.append(toJson(item));
            }
            return rendered.append("]").toString();
        }
        return "\"" + escape(value.toString()) + "\"";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> intMap(Object value) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Number number) {
                    result.put(String.valueOf(entry.getKey()), number.intValue());
                }
            }
        }
        return result;
    }

    /** Minimal JSON object parser sufficient for harness round-trips. */
    static Map<String, Object> parseObject(String json) {
        Parser parser = new Parser(json);
        Object parsed = parser.parseValue();
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a JSON object: " + json);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static final class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = json;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char current = json.charAt(index);
            return switch (current) {
                case '{' -> parseObjectValue();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObjectValue() {
            Map<String, Object> result = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                    continue;
                }
                if (next == '}') {
                    index++;
                    return result;
                }
                throw new IllegalArgumentException("Expected , or } at index " + index);
            }
        }

        private List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            index++;
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                    continue;
                }
                if (next == ']') {
                    index++;
                    return result;
                }
                throw new IllegalArgumentException("Expected , or ] at index " + index);
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder rendered = new StringBuilder();
            while (index < json.length()) {
                char current = json.charAt(index++);
                if (current == '"') {
                    return rendered.toString();
                }
                if (current == '\\' && index < json.length()) {
                    char escaped = json.charAt(index++);
                    rendered.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> {
                            String hex = json.substring(index, index + 4);
                            index += 4;
                            yield (char) Integer.parseInt(hex, 16);
                        }
                        default -> escaped;
                    });
                } else {
                    rendered.append(current);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Boolean parseBoolean() {
            if (json.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (json.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean at index " + index);
        }

        private Object parseNull() {
            if (json.startsWith("null", index)) {
                index += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null at index " + index);
        }

        private Number parseNumber() {
            int start = index;
            while (index < json.length() && "-+0123456789.eE".indexOf(json.charAt(index)) >= 0) {
                index++;
            }
            String token = json.substring(start, index);
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(token);
            }
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private char peek() {
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return json.charAt(index);
        }

        private void expect(char expected) {
            if (index >= json.length() || json.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected " + expected + " at index " + index);
            }
            index++;
        }
    }
}
