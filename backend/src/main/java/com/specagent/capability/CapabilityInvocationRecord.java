package com.specagent.capability;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Persisted row of the capability invocation log. */
public record CapabilityInvocationRecord(
        UUID id,
        String invocationKey,
        UUID projectId,
        UUID runId,
        String capabilityId,
        Map<String, Object> arguments,
        CapabilityResult.Status status,
        Map<String, Object> result,
        Instant createdAt,
        Instant completedAt) {
}
