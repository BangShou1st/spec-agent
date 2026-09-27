package com.specagent.modelsettings;

import com.specagent.modelsettings.ModelProviderRecord;
import com.specagent.modelsettings.ModelProviderViewService;
import com.specagent.modelsettings.ModelProviderViewService.ProviderView;
import com.specagent.modelsettings.ModelProvidersService;
import com.specagent.modelsettings.ProviderModelCatalogService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件名:ModelProvidersController.java
 *
 * 用途:提供商注册表的 REST 接口(/api/v1/settings/providers),把预设与
 * 用户自建行合成一份统一列表。这是系统的扩展点:新增提供商就是一次 POST,
 * 不需要新增控制器、表或枚举值。预设的请求形状比较特殊(OpenCode Zen 需要
 * 带额外头部的绝对直连调用,OpenRouter 有资格验证流程),其投影仍保留在
 * {@link ModelProviderViewService} 中,但对设置页而言它们只是列表里普通的两张卡片。
 */
@RestController
@RequestMapping("/api/v1/settings/providers")
public class ModelProvidersController {

    private final ModelProvidersService providers;
    private final ModelProviderViewService views;

    public ModelProvidersController(ModelProvidersService providers, ModelProviderViewService views) {
        this.providers = providers;
        this.views = views;
    }

    public record PatchRequest(String preset, String displayName, String apiFormat, String baseUrl,
                               String apiKey, String selectedModel, String modelSource) {
    }

    public record ProbeRequest(String apiFormat, String baseUrl, String apiKey) {
    }

    public record DiscoveryView(List<String> models, boolean manualModel, String endpointPreview) {
    }

    @GetMapping
    public ModelProviderViewService.ProviderListView list() {
        return views.listView();
    }

    @PostMapping
    public ProviderView create(@RequestBody PatchRequest request) {
        return views.rowView(providers.create(toPatch(request)));
    }

    @PutMapping("/{id}")
    public ProviderView update(@PathVariable String id, @RequestBody PatchRequest request) {
        return views.rowView(providers.update(requireRowId(id), toPatch(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        providers.delete(requireRowId(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ModelProviderViewService.ProviderListView activate(@PathVariable String id) {
        ModelProviderRecord record = providers.require(requireRowId(id));
        providers.requireActivatable(record);
        providers.activate(record);
        return list();
    }

    /** 草稿探测:卡片可以在保存之前先测试某个 Base URL / 协议。 */
    @PostMapping("/{id}/probe")
    public DiscoveryView probe(@PathVariable String id, @RequestBody ProbeRequest request) {
        String format = request == null ? null : request.apiFormat();
        String baseUrl = request == null ? null : request.baseUrl();
        String apiKey = request == null ? null : request.apiKey();
        return toView(providers.discover(requireRowId(id), format, baseUrl, apiKey));
    }

    /** 用已存密钥列出模型目录;不会要求重新提供密钥。 */
    @GetMapping("/{id}/models")
    public DiscoveryView models(@PathVariable String id) {
        return toView(providers.listModels(requireRowId(id)));
    }

    @PostMapping("/{id}/validate")
    public ProviderView validate(@PathVariable String id) {
        return views.rowView(providers.validate(requireRowId(id)));
    }

    private static DiscoveryView toView(ProviderModelCatalogService.Discovery discovery) {
        return new DiscoveryView(discovery.allModels(), discovery.manualModel(), discovery.endpointPreview());
    }

    private static ModelProvidersService.Patch toPatch(PatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        return new ModelProvidersService.Patch(request.preset(), request.displayName(), request.apiFormat(),
                request.baseUrl(), request.apiKey(), request.selectedModel(), request.modelSource());
    }

    /** 行 id 必须是 UUID;预设编码在这里不是合法目标。 */
    private static UUID requireRowId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown model provider: " + raw);
        }
    }
}
