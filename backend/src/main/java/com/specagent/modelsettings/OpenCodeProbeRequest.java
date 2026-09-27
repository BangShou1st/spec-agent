package com.specagent.modelsettings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 文件名:OpenCodeProbeRequest.java
 *
 * 用途:OpenCode 模型发现(probe)的请求载荷,携带待验证的 API 密钥,
 * 用于在保存之前探测该密钥下可用的模型列表。
 */
public record OpenCodeProbeRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 4096, message = "must be at most 4096 characters")
        String apiKey) {
}
