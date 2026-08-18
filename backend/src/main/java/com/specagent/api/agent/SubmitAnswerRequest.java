package com.specagent.api.agent;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Answer submission request.
 *
 * <p>{@code selectedOptionId} may reference an EXISTING runtime-owned option id
 * previously returned by the active node; clients may never create or assign
 * option ids. {@code freeText} may be absent only when a valid selected option
 * is present, and at least one meaningful input is required (enforced by the
 * runtime answer path). Free text is rejected when the active node does not
 * allow free-form answers.
 */
public record SubmitAnswerRequest(
        UUID selectedOptionId,
        @Size(max = 4000, message = "must be at most 4000 characters")
        String freeText) {
}