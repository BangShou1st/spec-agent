package com.specagent.api.common;

/**
 * Field-level validation detail inside an {@link ApiErrorResponse}.
 *
 * <p>Only the field name and a static validation reason are exposed; the
 * rejected value itself is never echoed back.
 */
public record ApiFieldError(String field, String message) {
}