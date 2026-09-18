package com.specagent.api.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Rename request: same title rules as creation. */
public record RenameProjectRequest(
        @NotBlank
        @Size(max = 255)
        String title) {
}
