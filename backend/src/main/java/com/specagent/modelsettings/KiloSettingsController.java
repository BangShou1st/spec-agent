package com.specagent.modelsettings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件名:KiloSettingsController.java
 *
 * 用途:Kilo Gateway 提供商设置的 REST 接口(/api/v1/settings/kilo),提供状态
 * 查询、密钥探测、模型列表、保存、仅切换模型与连通性验证端点。密钥只在请求进入
 * 时出现,响应永远只返回脱敏形式。
 */
@RestController
@RequestMapping("/api/v1/settings/kilo")
public class KiloSettingsController {

    private final KiloSettingsService service;

    public KiloSettingsController(KiloSettingsService service) {
        this.service = service;
    }

    public record StatusResponse(boolean configured, String maskedKey, String selectedModel) {
    }

    public record ProbeRequest(String apiKey) {
    }

    public record ProbeResponse(List<String> allModels, List<String> freeModels) {
    }

    public record SaveRequest(String apiKey, String selectedModel) {
    }

    public record ModelChangeRequest(
            @NotBlank(message = "must not be blank")
            @Size(max = 255, message = "must be at most 255 characters")
            String selectedModel) {
    }

    private static StatusResponse toResponse(KiloSettingsStatus status) {
        return new StatusResponse(status.configured(), status.maskedKey(), status.selectedModel());
    }

    private static ProbeResponse toModelResponse(KiloSettingsService.CandidateModels models) {
        return new ProbeResponse(models.allModels(), models.freeModels());
    }

    @GetMapping
    public StatusResponse status() {
        return toResponse(service.status());
    }

    @PostMapping("/probe")
    public ProbeResponse probe(@RequestBody ProbeRequest request) {
        String key = request == null ? null : request.apiKey();
        return toModelResponse(service.probe(key));
    }

    @GetMapping("/models")
    public ProbeResponse models() {
        return toModelResponse(service.listModels());
    }

    @PutMapping
    public StatusResponse save(@Valid @RequestBody SaveRequest request) {
        return toResponse(service.save(request.apiKey(), request.selectedModel()));
    }

    @PutMapping("/model")
    public StatusResponse changeModel(@Valid @RequestBody ModelChangeRequest request) {
        return toResponse(service.changeModel(request.selectedModel()));
    }

    /** 对已存配置做显式连通性测试;不会改动任何设置。 */
    @PostMapping("/validate")
    public StatusResponse validate() {
        return toResponse(service.validate());
    }
}
