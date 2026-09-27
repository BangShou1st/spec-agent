package com.specagent.modelsettings;

import java.util.Optional;

/**
 * 文件名:OpenCodeSettingsRepository.java
 *
 * 用途:OpenCode 设置的单行存储端口(find 读取、upsert 覆盖写),
 * 由 Jdbc 实现落地到 opencode_settings 表。
 */
public interface OpenCodeSettingsRepository {

    Optional<OpenCodeSettings> find();

    void upsert(OpenCodeSettings settings);
}
