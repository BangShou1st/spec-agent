package com.specagent.modelsettings;

import java.util.Optional;

/**
 * 文件名:CustomProviderSettingsRepository.java
 *
 * 用途:自定义提供商配置的仓储端口(单例配置,find 读取、upsert 写入、
 * markValidated 记录某次配置修订已通过兼容性验证),由 Jdbc 实现落地到数据库。
 */
public interface CustomProviderSettingsRepository {
    Optional<CustomProviderSettings> find();
    void upsert(CustomProviderSettings settings);
    void markValidated(long configRevision);
}
