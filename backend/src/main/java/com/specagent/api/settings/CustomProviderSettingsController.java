package com.specagent.api.settings;

import com.specagent.api.common.ApiErrorResponse;
import com.specagent.settings.custom.CustomProviderSettingsService;
import java.util.List;
import java.util.Set;
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
@RequestMapping("/api/v1/settings/custom")
public class CustomProviderSettingsController {

    private static final Set<String> FORMATS = Set.of("CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES");

    private final CustomProviderSettingsService service;

    public CustomProviderSettingsController(CustomProviderSettingsService service) {
        this.service = service;
    }

    public record StatusResponse(boolean configured, String apiFormat, String baseUrl, String endpointPreview,
                                 boolean hasKey, String maskedKey, String selectedModel, boolean manualModel,
                                 long configRevision, boolean validated) {
    }

    public record DiscoverRequest(String apiFormat, String baseUrl, String apiKey) {
    }

    public record DiscoverResponse(List<String> models, boolean manualModel, String endpointPreview) {
    }

    public record SaveRequest(String apiFormat, String baseUrl, String apiKey, String selectedModel,
                              String modelSource) {
    }

    private StatusResponse toResponse(CustomProviderSettingsService.Status s) {
        return new StatusResponse(s.configured(), s.apiFormat(), s.baseUrl(), s.endpointPreview(),
                s.hasKey(), s.maskedKey(), s.selectedModel(), s.manualModel(),
                s.configRevision(), s.validated());
    }

    @GetMapping
    public StatusResponse status() {
        return toResponse(service.status());
    }

    @PostMapping("/discover")
    public DiscoverResponse discover(@RequestBody DiscoverRequest request) {
        String format = request == null || request.apiFormat() == null
                ? "" : request.apiFormat().trim().toUpperCase();
        String baseUrl = request == null ? null : request.baseUrl();
        if (!FORMATS.contains(format)) {
            throw new IllegalArgumentException("Unknown api format");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("API Base URL is required");
        }
        var result = service.discover(format, baseUrl, request.apiKey());
        String preview = service.previewEndpoint(format, baseUrl);
        return new DiscoverResponse(result.models(), result.manualModel(), preview);
    }

    @PutMapping
    public StatusResponse save(@RequestBody SaveRequest request) {
        String format = request == null || request.apiFormat() == null
                ? "" : request.apiFormat().trim().toUpperCase();
        String baseUrl = request == null ? null : request.baseUrl();
        String model = request == null ? null : request.selectedModel();
        if (!FORMATS.contains(format)) {
            throw new IllegalArgumentException("Unknown api format");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("API Base URL is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model is required");
        }
        // apiKey null retains the stored key; empty clears; non-empty sets new.
        // modelSource MANUAL persists manual-model mode across reloads.
        return toResponse(service.save(format, baseUrl, request.apiKey(), model, request.modelSource()));
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
