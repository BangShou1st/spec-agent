package com.specagent.web;

import com.specagent.api.common.ApiErrorResponse;
import com.specagent.model.gateway.ModelGatewayErrorCategory;
import com.specagent.model.gateway.ModelGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps model-gateway failures into the stable API error contract.
 *
 * <p>This advice is the single bridge between the provider-neutral
 * {@link ModelGatewayException} vocabulary and the API error contract. Every
 * response carries a static, provider-neutral message — never the raw gateway
 * message, which could echo provider payloads — and never mentions any
 * concrete provider. The exception message is not logged either; only the
 * safe category is logged server-side.
 *
 * <p>It lives outside {@code com.specagent.api..} because the API boundary
 * intentionally has no dependency on model packages. It runs before the
 * generic advice so its exact-type handler wins over the catch-all
 * {@code Exception} mapping.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayErrorAdvice.class);

    @ExceptionHandler(ModelGatewayException.class)
    public ResponseEntity<ApiErrorResponse> handleGatewayFailure(ModelGatewayException ex) {
        ModelGatewayErrorCategory category = ex.gatewayCategory();
        LOG.warn("Model gateway failure category {}", category);
        return switch (category) {
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "MODEL_PROVIDER_TIMEOUT",
                    "The model provider did not respond in time");
            case CONNECTION -> error(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_UNREACHABLE",
                    "The model provider could not be reached");
            case AUTHENTICATION -> error(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_PROVIDER_AUTHENTICATION",
                    "The model provider rejected the request configuration");
            case RATE_LIMITED -> error(HttpStatus.TOO_MANY_REQUESTS, "MODEL_PROVIDER_RATE_LIMITED",
                    "The model provider is temporarily rate limited");
            case SERVER_ERROR -> error(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_ERROR",
                    "The model provider returned an internal error");
            case PROVIDER_REQUEST_ERROR -> error(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_REJECTED",
                    "The model provider rejected the request");
            case INVALID_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_INVALID_RESPONSE",
                    "The model provider returned an invalid response");
            case EMPTY_CONTENT -> error(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_PROVIDER_EMPTY_CONTENT",
                    "The model provider returned no usable content");
            case INVALID_MODEL -> error(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_PROVIDER_INVALID_MODEL",
                    "The configured model is not available");
            case NOT_CONFIGURED -> error(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_PROVIDER_NOT_CONFIGURED",
                    "The model provider is not configured");
        };
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message));
    }
}