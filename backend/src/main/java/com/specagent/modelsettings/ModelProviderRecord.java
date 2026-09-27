package com.specagent.modelsettings;

import com.specagent.model.contract.ModelProvider;
import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:ModelProviderRecord.java
 *
 * 用途:一个已存储的模型提供商行:预设类型 + 该行自己的密钥、协议格式和模型选择。
 * 这是设置页、激活门禁和运行时分发共同读取的唯一数据形状。{@code apiKey} 永远
 * 不会返回给浏览器——对外的 API 投影只暴露 {@code maskedSuffix}(脱敏后缀)。
 */
public record ModelProviderRecord(
        UUID id,
        ModelProvider preset,
        String displayName,
        String apiFormat,
        String baseUrl,
        String apiKey,
        String maskedSuffix,
        String selectedModel,
        String modelSource,
        long configRevision,
        Long validatedRevision,
        int position,
        Instant createdAt,
        Instant updatedAt,
        Instant validatedAt) {

    /** 判断是否存在已存密钥,但不暴露密钥本身。 */
    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 只有"实际被测过的那个修订号"才算验证通过;配置改过就要重测。 */
    public boolean validated() {
        return validatedRevision != null && validatedRevision == configRevision;
    }

    /** 选中模型是手动填写的,而非来自提供商目录发现。 */
    public boolean manualModel() {
        return "MANUAL".equals(modelSource);
    }

    /** 已配置 = 这一行现在就能真正提供推理服务。 */
    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank()
                && selectedModel != null && !selectedModel.isBlank()
                && (!preset.requiresApiKey() || hasKey());
    }

    /** 卡片标题和提供商标签上展示的名称,缺省回退到预设默认名。 */
    public String effectiveDisplayName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        return preset.defaultDisplayName();
    }
}
