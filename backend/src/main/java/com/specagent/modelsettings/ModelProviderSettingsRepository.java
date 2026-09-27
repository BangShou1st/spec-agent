package com.specagent.modelsettings;

import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:ModelProviderSettingsRepository.java
 *
 * 用途:激活提供商设置的单行存储端口,由 Jdbc 实现落地到 model_provider_settings 表。
 */
public interface ModelProviderSettingsRepository {
    Optional<ModelProviderSettings> find();

    /**
     * 持久化激活目标。{@code activeProviderId} 可为 null,
     * 对应通过遗留的"仅预设编码"路径激活的预设。
     */
    void setActive(String activeProvider, UUID activeProviderId);
}
