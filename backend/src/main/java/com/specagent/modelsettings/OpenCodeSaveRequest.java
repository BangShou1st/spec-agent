package com.specagent.modelsettings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 文件名:OpenCodeSaveRequest.java
 *
 * 用途:保存 OpenCode 设置的请求载荷,同时携带 API 密钥与选中的模型 id,
 * 两者均为必填并限制长度上限。
 */
public record OpenCodeSaveRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 4096, message = "must be at most 4096 characters")
        String apiKey,
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String selectedModel) {
}
