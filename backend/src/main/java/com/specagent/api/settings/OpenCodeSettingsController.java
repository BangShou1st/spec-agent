package com.specagent.api.settings;

import com.specagent.settings.opencode.OpenCodeSettingsService;
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
        return new OpenCodeProbeResponse(service.probe(request.apiKey()));
    }

    @PutMapping
    public OpenCodeSettingsResponse save(@Valid @RequestBody OpenCodeSaveRequest request) {
        return OpenCodeSettingsResponse.from(service.save(request.apiKey(), request.selectedModel()));
    }
}
