package com.specagent.api.settings;

import com.specagent.api.common.ApiErrorResponse;
import com.specagent.settings.custom.CustomProviderSettingsService;
import com.specagent.settings.openrouter.OpenRouterSettingsService;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.provider.ModelProviderSettingsService;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Active-provider persistence. Viewing a tab never activates; only this
 * explicit server-side endpoint changes the runtime provider after gates.
 */
@RestController
@RequestMapping("/api/v1/settings/providers")
public class ModelProviderSettingsController {

    private static final Set<String> ALLOWED = Set.of("OPENCODE_ZEN", "OPENROUTER", "CUSTOM");

    private final ModelProviderSettingsService providerSettings;
    private final OpenCodeSettingsService openCode;
    private final OpenRouterSettingsService openRouter;
    private final CustomProviderSettingsService custom;

    public ModelProviderSettingsController(ModelProviderSettingsService providerSettings,
                                           OpenCodeSettingsService openCode,
                                           OpenRouterSettingsService openRouter,
                                           CustomProviderSettingsService custom) {
        this.providerSettings = providerSettings;
        this.openCode = openCode;
        this.openRouter = openRouter;
        this.custom = custom;
    }

    public record ActiveResponse(String activeProvider) {
    }

    public record ActivateRequest(String provider) {
    }

    @GetMapping("/active")
    public ActiveResponse active() {
        return new ActiveResponse(providerSettings.activeProviderCode());
    }

    @PostMapping("/activate")
    public ActiveResponse activate(@RequestBody ActivateRequest request) {
        String target = request == null || request.provider() == null
                ? "" : request.provider().trim().toUpperCase();
        if (!ALLOWED.contains(target)) {
            throw new IllegalArgumentException("Unknown provider");
        }
        switch (target) {
            case "OPENCODE_ZEN" -> openCode.requireRuntimeSettings();
            case "OPENROUTER" -> openRouter.requireActivatable();
            case "CUSTOM" -> custom.requireActivatable();
            default -> throw new IllegalArgumentException("Unknown provider");
        }
        providerSettings.setActiveProviderByCode(target);
        return new ActiveResponse(target);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        String msg = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Request validation failed" : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", msg));
    }
}
