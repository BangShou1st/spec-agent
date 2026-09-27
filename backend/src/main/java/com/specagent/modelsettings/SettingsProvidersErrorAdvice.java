package com.specagent.modelsettings;

import com.specagent.modelsettings.CustomProviderSettingsController;
import com.specagent.modelsettings.OpenRouterSettingsController;

import com.specagent.common.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 文件名:SettingsProvidersErrorAdvice.java
 *
 * 用途:提供商设置相关控制器的共享错误映射。IllegalArgumentException 代表
 * 调用方入参校验失败,以 400/VALIDATION_ERROR 返回;其他异常保持中央
 * {@code ApiExceptionHandler} 的策略(500)。
 */
@RestControllerAdvice(assignableTypes = {
        ModelProvidersController.class,
        ModelProviderSettingsController.class,
        CustomProviderSettingsController.class,
        OpenRouterSettingsController.class})
public class SettingsProvidersErrorAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        String msg = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Request validation failed" : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", msg));
    }
}
