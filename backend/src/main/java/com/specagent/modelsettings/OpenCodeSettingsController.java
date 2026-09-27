package com.specagent.modelsettings;

import com.specagent.modelsettings.OpenCodeSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件名:OpenCodeSettingsController.java
 *
 * 用途:OpenCode(Zen)提供商设置的 REST 接口(/api/v1/settings/opencode),
 * 提供状态查询、密钥探测、已存密钥模型列表、保存、仅切换模型、连通性验证等端点。
 * 密钥只在请求进入时出现,响应永远只返回脱敏形式。
 */
@RestController
@RequestMapping("/api/v1/settings/opencode")
public class OpenCodeSettingsController {

    private final OpenCodeSettingsService service;

    public OpenCodeSettingsController(OpenCodeSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public OpenCodeSettingsResponse status() {
        return OpenCodeSettingsResponse.from(service.status());
    }

    @PostMapping("/probe")
    public OpenCodeProbeResponse probe(@Valid @RequestBody OpenCodeProbeRequest request) {
        return toModelResponse(service.probe(request.apiKey()));
    }

    @GetMapping("/models")
    public OpenCodeProbeResponse models() {
        return toModelResponse(service.listSavedKeyModels());
    }

    private static OpenCodeProbeResponse toModelResponse(
            OpenCodeSettingsService.OpenCodeCandidateModels models) {
        return new OpenCodeProbeResponse(models.allModels(), models.freeModels());
    }

    @PutMapping
    public OpenCodeSettingsResponse save(@Valid @RequestBody OpenCodeSaveRequest request) {
        return OpenCodeSettingsResponse.from(service.save(request.apiKey(), request.selectedModel()));
    }

    @PutMapping("/model")
    public OpenCodeSettingsResponse changeModel(
            @Valid @RequestBody OpenCodeModelChangeRequest request) {
        return OpenCodeSettingsResponse.from(service.changeModel(request.selectedModel()));
    }

    /** 对已存配置对做显式连通性测试;不会改动任何设置。 */
    @PostMapping("/validate")
    public OpenCodeSettingsResponse validate() {
        return OpenCodeSettingsResponse.from(service.validate());
    }
}
