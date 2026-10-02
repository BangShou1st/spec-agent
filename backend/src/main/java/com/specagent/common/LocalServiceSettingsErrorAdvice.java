package com.specagent.common;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Never reflect exception/provider messages, including credential validation input. */
@Order(-100)
@RestControllerAdvice(annotations=LocalServiceSettingsApi.class)
public class LocalServiceSettingsErrorAdvice {
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    public ResponseEntity<ApiErrorResponse> failure(RuntimeException ex) {
        String message=ex.getMessage();
        String code=message!=null && message.matches("[A-Z][A-Z_]{2,79}")?message:"SERVICE_SETTINGS_FAILED";
        return ResponseEntity.status(ex instanceof IllegalArgumentException?400:409).body(ApiErrorResponse.of(code,"服务配置操作失败，请检查设置或重新加载后再试"));
    }
}
