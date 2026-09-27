package com.specagent.modelsettings;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:ModelProviderSettings.java
 *
 * 用途:全局单例的"当前激活运行时提供商"设置。{@code activeProvider} 保留
 * 预设编码以兼容既有读取方和预设激活路径;{@code activeProviderId} 指向具体行,
 * 使多个用户自建提供商可以相互区分。预设种子行两者指向同一提供商。
 */
public record ModelProviderSettings(String activeProvider, UUID activeProviderId, Instant updatedAt) {
}
