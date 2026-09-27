package com.specagent.modelsettings;

import java.util.Optional;

/**
 * 文件名:OpenRouterSettingsRepository.java
 *
 * 用途:OpenRouter 设置的单行存储端口(find/upsert/markValidated),
 * markValidated 只在修订号未变时生效,由 Jdbc 实现落地到 openrouter_settings 表。
 */
public interface OpenRouterSettingsRepository {
    Optional<OpenRouterSettings> find();
    void upsert(OpenRouterSettings settings);
    void markValidated(long configRevision);
}
