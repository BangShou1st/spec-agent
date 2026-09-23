package com.specagent.modelsettings;

import com.specagent.modelsettings.OpenCodeSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /** Explicit reachability test on the stored pair; never mutates settings. */
    @PostMapping("/validate")
    public OpenCodeSettingsResponse validate() {
        return OpenCodeSettingsResponse.from(service.validate());
    }
}
