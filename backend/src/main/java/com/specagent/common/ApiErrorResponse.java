package com.specagent.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 文件名:ApiErrorResponse.java
 *
 * 用途:后端统一的 API 错误响应契约,保证所有接口返回结构稳定的错误信息。
 *
 * {@code code} 是稳定的机器可读标识(例如 {@code PROJECT_NOT_FOUND} 或
 * {@code VALIDATION_ERROR}),{@code message} 是面向人的安全摘要,
 * {@code errors} 在校验失败时携带可选的字段级结构化明细。响应中绝不包含
 * 堆栈、SQL、凭据、原始 prompt 或原始模型/供应商负载。
 *
 * 线上报文结构不变,仅包位置迁移(原先在 {@code com.specagent.web}),
 * 这样应用层抛出同样错误时不必依赖 HTTP 边界。
 */
public record ApiErrorResponse(
        String code,
        String message,
        String timestamp,
        List<ApiFieldError> errors,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> details) {

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(code, message, Instant.now().toString(), List.of(), Map.of());
    }

    public static ApiErrorResponse of(String code, String message, List<ApiFieldError> errors) {
        return new ApiErrorResponse(code, message, Instant.now().toString(), errors, Map.of());
    }

    public static ApiErrorResponse of(String code, String message, Map<String, String> details) {
        return new ApiErrorResponse(code, message, Instant.now().toString(), List.of(), details);
    }
}
