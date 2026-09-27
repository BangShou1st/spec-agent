package com.specagent.web;

import com.specagent.common.ApiErrorResponse;
import com.specagent.model.contract.ModelGatewayErrorCategory;
import com.specagent.model.contract.ModelGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 文件名:GatewayErrorAdvice.java
 *
 * 用途:把模型网关失败映射到稳定的 API 错误契约,是供应商侧异常
 * 进入 HTTP 边界的唯一通道。
 *
 * 本 advice 是供应商中立的 {@link ModelGatewayException} 词汇与 API
 * 错误契约之间的唯一桥梁。每个响应都携带静态、供应商中立的消息——绝不
 * 透传原始网关消息(那可能回显供应商负载),也绝不提及任何具体供应商。
 * 异常消息同样不写日志;服务端只记录安全的类别枚举。
 *
 * 它放在 {@code com.specagent.api..} 之外,因为 API 边界刻意不依赖
 * model 包。它先于通用 advice 执行,精确类型处理器优先于兜底的
 * {@code Exception} 映射生效。
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