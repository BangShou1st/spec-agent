package com.specagent.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Explicit application-level failure with a stable error code and HTTP status.
 *
 * <p>The shared error kernel: it lives in {@code com.specagent.common} next to
 * {@code PreciseConflictException} so the application/orchestration layer and
 * the HTTP boundary can both signal the same failure. The HTTP mapping itself
 * stays at the edge
 * ({@code com.specagent.web.ApiExceptionHandler}), which is the only
 * place that knows about {@code @RestControllerAdvice}.
 *
 * <p>Thrown by API components and application services when a request cannot be
 * satisfied. The handler maps it to the stable {@link ApiErrorResponse}
 * contract. Messages are static and safe; they never carry stack traces, SQL,
 * credentials, or provider payloads.
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
