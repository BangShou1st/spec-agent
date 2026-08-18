package com.specagent.api.common;

import org.springframework.http.HttpStatus;

/**
 * Explicit API-level failure with a stable error code and HTTP status.
 *
 * <p>Thrown by API components (controllers and thin query services) when a
 * request cannot be satisfied. The handler maps it to the stable
 * {@link ApiErrorResponse} contract. Messages are static and safe; they never
 * carry stack traces, SQL, credentials, or provider payloads.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
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
}