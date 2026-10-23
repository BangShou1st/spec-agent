package com.specagent.modelsettings;

import com.specagent.model.contract.ActiveModelTargetPort;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.KiloRuntimeSettingsPort;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 文件名:ActiveModelTargetService.java
 *
 * 用途:{@link ActiveModelTargetPort} 的唯一实现:按当前激活提供商,从<b>与该提供商
 * 推理网关完全相同的运行时设置端口</b>读取一次不可变配置快照。快照同时提供模型、凭据
 * 和端点/配置修订信息,路由冻结与实际 HTTP 使用同一份事实。
 *
 * <p>只读:不写任何设置、不做选择、不触发网络;所有凭据解析仍留在各端口内部。
 * 提供商未配置时按端口自身的失败关闭行为抛出,由调用方按"目标不可用"处理。
 */
@Service
public class ActiveModelTargetService implements ActiveModelTargetPort {

    private final ModelProviderSettingsService providers;
    private final OpenCodeRuntimeSettingsPort openCode;
    private final KiloRuntimeSettingsPort kilo;
    private final CustomRuntimeSettingsPort custom;

    public ActiveModelTargetService(ModelProviderSettingsService providers,
                                    OpenCodeRuntimeSettingsPort openCode,
                                    KiloRuntimeSettingsPort kilo,

                                    CustomRuntimeSettingsPort custom) {
        this.providers = providers;
        this.openCode = openCode;
        this.kilo = kilo;
        this.custom = custom;
    }

    @Override
    public Optional<Target> activeTarget() {
        return targetForProvider(providers.activeProvider());
    }

    private Optional<Target> targetForProvider(ModelProvider provider) {
        Object runtime = switch (provider) {
            case OPENCODE_ZEN -> openCode.requireRuntimeSettings();
            case KILO -> kilo.requireRuntimeSettings();

            case CUSTOM -> custom.requireRuntimeSettings();
        };
        String model = switch (runtime) {
            case com.specagent.model.contract.RuntimeOpenCodeSettings value -> value.selectedModel();
            case com.specagent.model.contract.RuntimeKiloSettings value -> value.selectedModel();

            case com.specagent.model.contract.RuntimeCustomSettings value -> value.selectedModel();
            default -> throw new IllegalStateException("Unsupported active model settings");
        };
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Target(provider.name(), model, runtime));
    }

    @Override
    public Optional<Target> targetForConnectionReference(String reference) {
        if ("builtin:OPENCODE_ZEN".equals(reference)) return targetForProvider(ModelProvider.OPENCODE_ZEN);
        if ("builtin:KILO".equals(reference)) return targetForProvider(ModelProvider.KILO);
        if (reference == null || !reference.startsWith("custom-provider:")) return Optional.empty();
        var settings = custom.requireRuntimeSettings(reference);
        return Optional.of(new Target(ModelProvider.CUSTOM.name(), settings.selectedModel(), settings));
    }
}
