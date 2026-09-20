/**
 * HTTP error mapping for the API boundary.
 *
 * <p>The shared error kernel ({@code ApiException}, {@code ApiErrorResponse},
 * {@code ApiFieldError}) lives in {@code com.specagent.common} so application
 * services can raise the same failures; only the {@code @RestControllerAdvice}
 * that translates them into HTTP responses stays here.
 */
package com.specagent.api.common;
