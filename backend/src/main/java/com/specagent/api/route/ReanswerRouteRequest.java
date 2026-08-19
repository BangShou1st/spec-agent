package com.specagent.api.route;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Explicit-source command for exploring a different answer to one question. */
public record ReanswerRouteRequest(
        @NotNull(message = "must be provided")
        UUID sourceRouteId,
        @Size(max = 255, message = "must be at most 255 characters")
        String label) {
}
