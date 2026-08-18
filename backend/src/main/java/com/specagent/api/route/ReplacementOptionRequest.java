package com.specagent.api.route;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A replacement option in a regenerate request. Only client-owned content
 * (label, impact) is accepted; runtime-owned option ids are created by the
 * runtime and never supplied by clients.
 */
public record ReplacementOptionRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 500, message = "must be at most 500 characters")
        String label,
        @Size(max = 2000, message = "must be at most 2000 characters")
        String impact) {
}