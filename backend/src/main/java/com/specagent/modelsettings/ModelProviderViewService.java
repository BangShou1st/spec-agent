package com.specagent.modelsettings;

import com.specagent.model.contract.ModelProvider;
import com.specagent.modelsettings.CustomProviderSettingsService;
import com.specagent.modelsettings.OpenCodeSettingsService;
import com.specagent.modelsettings.OpenRouterSettingsService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 文件名:ModelProviderViewService.java
 *
 * 用途:把提供商注册表(预设卡片 + 用户自建行)投影成设置页可渲染的视图形状。
 * 预设相关的知识——卡片可展示哪些操作、预设的默认 Base URL 与显示名称——
 * 集中在本服务里,API 层只消费成品视图,不直接触碰 {@code model.provider}。
 */
@Service
public class ModelProviderViewService {

    private final ModelProvidersService providers;
    private final ModelProviderSettingsService active;
    private final OpenCodeSettingsService openCode;
    private final OpenRouterSettingsService openRouter;
    private final CustomProviderSettingsService custom;

    public ModelProviderViewService(ModelProvidersService providers, ModelProviderSettingsService active,
                                    OpenCodeSettingsService openCode, OpenRouterSettingsService openRouter,
                                    CustomProviderSettingsService custom) {
        this.providers = providers;
        this.active = active;
        this.openCode = openCode;
        this.openRouter = openRouter;
        this.custom = custom;
    }

    /** 该预设的卡片允许展示的编辑能力。 */
    public record Capabilities(boolean userNamed, boolean selectableFormat, boolean editableBaseUrl,
                               boolean requiresApiKey, boolean builtInCatalog) {
    }

    public record ProviderView(String id, String preset, String displayName, String apiFormat, String baseUrl,
                               String endpointPreview, boolean hasKey, String maskedKey, String selectedModel,
                               boolean manualModel, boolean configured, long configRevision, boolean validated,
                               boolean active, Capabilities capabilities) {
    }

    public record ProviderListView(List<ProviderView> providers, String activeProvider, String activeProviderId) {
    }

    /** 把预设与用户自建行合成一份统一列表。 */
    public ProviderListView listView() {
        List<ProviderView> views = new ArrayList<>();
        views.add(presetView(ModelProvider.OPENCODE_ZEN));
        views.add(presetView(ModelProvider.OPENROUTER));
        for (ModelProviderRecord record : providers.list()) {
            views.add(rowView(record));
        }
        return new ProviderListView(views, active.activeProviderCode(),
                active.activeProviderId() == null ? null : active.activeProviderId().toString());
    }

    public ProviderView rowView(ModelProviderRecord record) {
        return new ProviderView(
                record.id().toString(),
                record.preset().name(),
                record.effectiveDisplayName(),
                record.apiFormat(),
                record.baseUrl(),
                custom.previewEndpoint(record.apiFormat(), record.baseUrl()),
                record.hasKey(),
                mask(record.maskedSuffix()),
                record.selectedModel(),
                record.manualModel(),
                record.configured(),
                record.configRevision(),
                record.validated(),
                active.isActiveId(record.id()),
                capabilitiesOf(record.preset()));
    }

    private ProviderView presetView(ModelProvider preset) {
        // 预设行不落在 model_providers 表里,状态直接从各自的设置服务读取
        if (preset == ModelProvider.OPENCODE_ZEN) {
            var status = openCode.status();
            return presetStatusView(preset, status.configured(), status.maskedKey(),
                    status.selectedModel(), 0L, false);
        }
        boolean isActive = active.isActiveCode(preset.name());
        var status = openRouter.status(isActive);
        return presetStatusView(preset, status.configured(), status.maskedKey(),
                status.selectedModel(), status.configRevision(), status.validated());
    }

    private ProviderView presetStatusView(ModelProvider preset, boolean configured, String maskedKey,
                                          String selectedModel, long configRevision, boolean validated) {
        boolean isActive = active.isActiveCode(preset.name()) && active.activeProviderId() == null;
        return new ProviderView(
                preset.name(), preset.name(), preset.defaultDisplayName(), "CHAT_COMPLETIONS",
                preset.defaultBaseUrl(), null, maskedKey != null, maskedKey, selectedModel, false,
                configured, configRevision, validated, isActive, capabilitiesOf(preset));
    }

    private static Capabilities capabilitiesOf(ModelProvider preset) {
        return new Capabilities(preset.userNamed(), preset.selectableFormat(), preset.editableBaseUrl(),
                preset.requiresApiKey(), preset.builtInCatalog());
    }

    private static String mask(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return null;
        }
        return "••••" + suffix;
    }
}
