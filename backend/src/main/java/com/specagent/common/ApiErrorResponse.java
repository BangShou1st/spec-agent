package com.specagent.common;

import java.time.Instant;
import java.util.List;

/**
 * Stable API error contract.
 *
 * <p>{@code code} is a stable machine-readable identifier (for example
 * {@code PROJECT_NOT_FOUND} or {@code VALIDATION_ERROR}), {@code message} is a
 * safe human-readable summary, and {@code errors} carries optional structured
 * field-level detail for validation failures. The response never contains a
 * stack trace, SQL, credentials, raw prompts, or raw model/provider payloads.
 *
 * <p>Wire shape is unchanged; only the package moved (formerly
 * {@code com.specagent.api.common}) so the application layer can raise the same
 * errors without depending on the HTTP boundary.
 */
public record ApiErrorResponse(
        String code,
        String message,
        String timestamp,
        List<ApiFieldError> errors) {

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(code, message, Instant.now().toString(), List.of());
    }

    public static ApiErrorResponse of(String code, String message, List<ApiFieldError> errors) {
        return new ApiErrorResponse(code, message, Instant.now().toString(), errors);
    }
}
