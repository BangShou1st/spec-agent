package com.specagent.modelsettings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 文件名:OpenCodeModelChangeRequest.java
 *
 * 用途:OpenCode 设置页"仅切换选中模型"的请求载荷,
 * 只携带新的模型 id,复用已存密钥,不影响其他配置。
 */
public record OpenCodeModelChangeRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String selectedModel) {
}
