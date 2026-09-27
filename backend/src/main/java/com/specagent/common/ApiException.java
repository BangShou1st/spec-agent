package com.specagent.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 文件名:ApiException.java
 *
 * 用途:带稳定错误码和 HTTP 状态码的显式应用层异常,是后端统一的业务失败
 * 信号。
 *
 * 属于共享错误内核:放在 {@code com.specagent.common} 中,与
 * {@code PreciseConflictException} 相邻,让应用/编排层和 HTTP 边界都能抛出
 * 同一种失败。HTTP 映射本身留在边界
 * ({@code com.specagent.web.ApiExceptionHandler}),那里是唯一认识
 * {@code @RestControllerAdvice} 的地方。
 *
 * 当请求无法被满足时,由 API 组件和应用服务抛出。异常处理器会把它映射到
 * 稳定的 {@link ApiErrorResponse} 契约。消息是静态且安全的,绝不携带堆栈、
 * SQL、凭据或供应商负载。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> details;

    private ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    private ApiException(HttpStatus status, String code, String message,
                         Map<String, String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException conflict(String code, String message,
                                        Map<String, String> details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, details);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException internal(String code, String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, String> details() {
        return details;
    }
}
