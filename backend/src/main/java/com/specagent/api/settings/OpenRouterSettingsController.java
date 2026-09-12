package com.specagent.api.settings;

import com.specagent.api.common.ApiErrorResponse;
import com.specagent.settings.openrouter.OpenRouterSettingsService;
import com.specagent.settings.provider.ModelProviderSettingsService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/openrouter")
public class OpenRouterSettingsController {

    private final OpenRouterSettingsService service;
    private final ModelProviderSettingsService providers;

    public OpenRouterSettingsController(OpenRouterSettingsService service, ModelProviderSettingsService providers) {
        this.service = service;
        this.providers = providers;
    }

    public record StatusResponse(boolean configured, String maskedKey, String selectedModel,
                                 long configRevision, boolean validated, boolean active) {
    }

    public record ProbeRequest(String apiKey) {
    }

    public record ProbeResponse(List<String> freeModels) {
    }

    public record SaveRequest(String apiKey, String selectedModel) {
    }

    private StatusResponse toResponse(OpenRouterSettingsService.Status s) {
        boolean active = providers.isActiveCode("OPENROUTER");
        return new StatusResponse(s.configured(), s.maskedKey(), s.selectedModel(),
                s.configRevision(), s.validated(), active);
    }

    @GetMapping
    public StatusResponse status() {
        return toResponse(service.status(providers.isActiveCode("OPENROUTER")));
    }

    @PostMapping("/probe")
    public ProbeResponse probe(@RequestBody ProbeRequest request) {
        String key = request == null ? null : request.apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("OpenRouter API key is required");
        }
        return new ProbeResponse(service.probeCandidate(key));
    }

    @GetMapping("/models")
    public ProbeResponse models() {
        return new ProbeResponse(service.listSavedKeyModels());
    }

    @PutMapping
    public StatusResponse save(@RequestBody SaveRequest request) {
        String model = request == null ? null : request.selectedModel();
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model is required");
        }
        String key = request.apiKey();
        return toResponse(service.save(key, model));
    }

    @PostMapping("/validate")
    public StatusResponse validate() {
        return toResponse(service.validate());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        String msg = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Request validation failed" : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", msg));
    }
}
