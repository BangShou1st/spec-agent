package com.specagent.eval;

import java.util.Locale;

/** Deterministic classification of live provider/infrastructure outcomes. */
public final class LiveFailureClassifier {

    private LiveFailureClassifier() {
    }

    public static boolean isInfrastructureFailure(ObservationEnvelope observation) {
        if (observation == null) {
            return false;
        }
        if (observation.violations().stream().anyMatch(violation ->
                violation.failureClass() == FailureClass.PROVIDER_FAILURE)) {
            return true;
        }
        String result = observation.executionResult();
        if (result == null || !result.startsWith("failed:")) {
            return false;
        }
        String detail = result.substring("failed:".length()).toLowerCase(Locale.ROOT);
        // RUN_FAILED is also used for deterministic runtime/domain failures
        // (for example SHARED_STATE_DIVERGENCE). Only provider/Brain/gateway
        // failure evidence belongs in the reliability dimension.
        return detail.contains("agentbrainunavailable")
                || detail.contains("brain_unavailable")
                || detail.contains("modelgateway")
                || detail.contains("opencodemodel")
                || detail.contains("provider")
                || detail.contains("timeout")
                || detail.contains("timed out")
                || detail.contains("rate limit")
                || detail.contains("rate_limited")
                || detail.matches(".*(?<!\\d)(401|402|403|408|429|500|502|503|504)(?!\\d).*");
    }

    public static ProviderFailureClass classify(ObservationEnvelope observation) {
        if (!isInfrastructureFailure(observation)) {
            return null;
        }
        String detail = ((observation.executionResult() == null ? "" : observation.executionResult())
                + " " + observation.violations()).toLowerCase(Locale.ROOT);
        if (containsStatus(detail, "401")) {
            return ProviderFailureClass.UNAUTHORIZED_401;
        }
        if (containsStatus(detail, "429") || detail.contains("rate limit")
                || detail.contains("rate_limited")) {
            return ProviderFailureClass.RATE_LIMIT_429;
        }
        if (containsStatus(detail, "500") || containsStatus(detail, "502")
                || containsStatus(detail, "503") || containsStatus(detail, "504")
                || detail.contains("5xx")) {
            return ProviderFailureClass.SERVER_5XX;
        }
        if (detail.contains("timeout") || detail.contains("timed out")) {
            return ProviderFailureClass.TIMEOUT;
        }
        if (detail.contains("brainunavailable") || detail.contains("brain unavailable")
                || detail.contains("agent brain")) {
            return ProviderFailureClass.BRAIN_UNAVAILABLE;
        }
        if (detail.contains("transport") || detail.contains("connection")
                || detail.contains("httpclient")) {
            return ProviderFailureClass.TRANSPORT_FAILURE;
        }
        return ProviderFailureClass.UNKNOWN;
    }

    /** Identifies model/broker contract parsing failures for separate reporting. */
    public static boolean isSchemaFailure(ObservationEnvelope observation) {
        if (observation == null) {
            return false;
        }
        if (observation.violations().stream().anyMatch(violation ->
                violation.failureClass() == FailureClass.BRAIN_SCHEMA)) {
            return true;
        }
        return observation.semanticTrace().stages().values().stream()
                .map(stage -> stage.get("error_type"))
                .filter(value -> value != null)
                .map(value -> String.valueOf(value).toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("modelcontract")
                        || value.contains("schema")
                        || value.contains("invalidjson"));
    }

    private static boolean containsStatus(String value, String status) {
        return value.matches(".*(?<!\\d)" + status + "(?!\\d).*");
    }
}
