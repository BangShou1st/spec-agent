package com.specagent.eval;

import java.util.Locale;

/**
 * 文件名:LiveFailureClassifier.java
 *
 * 用途:对 live provider / 基础设施失败做确定性分类:判断一次尝试是否
 * 属于基础设施失败(provider、Brain、网关、超时、限流、5xx 等),并进一步
 * 归入 {@link ProviderFailureClass};同时单独识别模型/契约 schema 类失败。
 * 用于可靠性维度的统计,避免把 provider 故障误记为行为失败。
 *
 * 协作:被 {@link EvalSummary} / {@link CausalReportGenerator} 消费,
 * 从 {@link ObservationEnvelope} 的执行结果与违例中读取证据。
 */
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
        // RUN_FAILED 也被确定性的运行时/领域失败使用(例如 SHARED_STATE_DIVERGENCE)。
        // 只有 provider/Brain/网关的失败证据才归入可靠性维度。
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

    /** 识别模型/broker 契约解析失败,单独报告。 */
    public static boolean isSchemaFailure(ObservationEnvelope observation) {
        if (observation == null) {
            return false;
        }
        if (observation.violations().stream().anyMatch(violation ->
                violation.failureClass() == FailureClass.BRAIN_SCHEMA)) {
            return true;
        }
        String result = observation.executionResult();
        if (result != null) {
            String detail = result.toLowerCase(Locale.ROOT);
            // 类型化的 brain/模型输出错误码属于 schema 失败,绝不归为
            // 基础设施失败:服务正常工作并拒绝了该输出。
            if (detail.contains("model_contract_violation")
                    || detail.contains("model_ungrounded_reference")) {
                return true;
            }
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
