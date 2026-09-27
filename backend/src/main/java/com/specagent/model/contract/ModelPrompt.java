package com.specagent.model.contract;

/**
 * 文件名:ModelPrompt.java
 *
 * 用途:交给模型网关、用于一次 agent 推理步骤的带版本提示词。提示词由固定的
 * 系统提示词(系统策略 + 任务指令)和用户提示词(任务编码 + 运行时上下文 JSON)
 * 组成。版本号标识本次使用的是哪一版线上提示词契约,使轨迹记录能把模型输出归因
 * 到具体的提示词版本。
 */
public record ModelPrompt(
        String version,
        String systemPrompt,
        String userPrompt
) {
    public ModelPrompt {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Prompt version is required");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("System prompt is required");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("User prompt is required");
        }
    }
}
