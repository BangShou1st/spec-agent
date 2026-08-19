package com.specagent.api.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OpenCodeProbeRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 4096, message = "must be at most 4096 characters")
        String apiKey) {
}
