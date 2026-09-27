package com.specagent.modelsettings;

import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.ModelProvider;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 文件名:ModelProviderSettingsService.java
 *
 * 用途:全局单例的激活提供商设置服务,升级场景下默认激活 OPENCODE_ZEN。
 * 激活目标被刻意记录两份:预设编码保持原有路由行为不变,行 id 用于区分
 * 多个用户自建提供商。通过遗留"仅编码"路径激活预设时,行 id 为 null。
 */
@Service
public class ModelProviderSettingsService implements ActiveProviderPort {

    private final ModelProviderSettingsRepository repository;

    public ModelProviderSettingsService(ModelProviderSettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    public ModelProvider activeProvider() {
        return repository.find()
                .map(s -> ModelProvider.fromCode(s.activeProvider()))
                .orElse(ModelProvider.OPENCODE_ZEN);
    }

    public String activeProviderCode() {
        return activeProvider().name();
    }

    /** 激活的具体行 id;通过编码激活预设时为 null。 */
    public UUID activeProviderId() {
        return repository.find().map(ModelProviderSettings::activeProviderId).orElse(null);
    }

    public boolean isActiveCode(String code) {
        return activeProviderCode().equals(code);
    }

    public boolean isActiveId(UUID id) {
        return id != null && id.equals(activeProviderId());
    }

    public void setActiveProvider(ModelProvider provider) {
        repository.setActive(provider.name(), null);
    }

    /** 面向 API 的便捷方法,避免控制器直接依赖 model 包。 */
    public void setActiveProviderByCode(String code) {
        repository.setActive(ModelProvider.fromCode(code).name(), null);
    }

    /** 激活一个具体行;预设编码从该行自身推导。 */
    public void setActiveTarget(String code, UUID activeProviderId) {
        repository.setActive(ModelProvider.fromCode(code).name(), activeProviderId);
    }
}
