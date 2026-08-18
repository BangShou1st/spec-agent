/**
 * Web/transport boundary bridge.
 *
 * <p>Holds the single {@code @RestControllerAdvice} that maps provider/gateway
 * failures into the API error contract. It lives outside {@code com.specagent.api..}
 * because the API boundary must not depend on {@code com.specagent.model..}
 * packages; this bridge is the one place where model-gateway exception types
 * meet API DTOs, and it exposes only static, provider-neutral messages.
 */
package com.specagent.web;