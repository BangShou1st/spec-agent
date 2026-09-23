package com.specagent.modelsettings;

import com.specagent.modelsettings.CustomProviderSettingsController;
import com.specagent.modelsettings.OpenRouterSettingsController;

import com.specagent.common.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Shared error mapping for the provider settings controllers. IllegalArgumentException
 * means caller-supplied validation failure, answered as 400/VALIDATION_ERROR;
 * anything else keeps the central {@code ApiExceptionHandler} policy (500).
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
