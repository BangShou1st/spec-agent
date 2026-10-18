package com.specagent.modelsettings;

import com.specagent.model.contract.ActiveModelTargetPort;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.KiloRuntimeSettingsPort;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import com.specagent.model.contract.OpenRouterRuntimeSettingsPort;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 文件名:ActiveModelTargetService.java
 *
 * 用途:{@link ActiveModelTargetPort} 的唯一实现:按当前激活提供商,从<b>与该提供商
 * 推理网关完全相同的运行时设置端口</b>读取选中模型。这保证"冻结读取"与"网关实际使用"
 * 同源同值,不会出现两份不一致的模型事实。
 *
 * <p>只读:不写任何设置、不做选择、不触发网络;所有凭据解析仍留在各端口内部。
 * 提供商未配置时按端口自身的失败关闭行为抛出,由调用方按"目标不可用"处理。
 */
@Service
public class ActiveModelTargetService implements ActiveModelTargetPort {

    private final ModelProviderSettingsService providers;
    private final OpenCodeRuntimeSettingsPort openCode;
    private final KiloRuntimeSettingsPort kilo;
    private final OpenRouterRuntimeSettingsPort openRouter;
    private final CustomRuntimeSettingsPort custom;

    public ActiveModelTargetService(ModelProviderSettingsService providers,
                                    OpenCodeRuntimeSettingsPort openCode,
                                    KiloRuntimeSettingsPort kilo,
                                    OpenRouterRuntimeSettingsPort openRouter,
                                    CustomRuntimeSettingsPort custom) {
        this.providers = providers;
        this.openCode = openCode;
        this.kilo = kilo;
        this.openRouter = openRouter;
        this.custom = custom;
    }

    @Override
    public Optional<Target> activeTarget() {
        ModelProvider provider = providers.activeProvider();
        String model = switch (provider) {
            case OPENCODE_ZEN -> openCode.requireRuntimeSettings().selectedModel();
            case KILO -> kilo.requireRuntimeSettings().selectedModel();
            case OPENROUTER -> openRouter.requireRuntimeSettings().selectedModel();
            case CUSTOM -> custom.requireRuntimeSettings().selectedModel();
        };
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Target(provider.name(), model));
    }
}
