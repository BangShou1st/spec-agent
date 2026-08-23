package com.specagent.capability;

import java.util.Map;
import java.util.UUID;

/**
 * One capability invocation requested by an accepted action. The invocation
 * key is runtime-owned idempotency metadata: replays with the same key
 * return the recorded result instead of re-executing side effects.
 */
public record CapabilityInvocation(
        UUID invocationId,
        String invocationKey,
        String capabilityId,
        UUID projectId,
        UUID runId,
        Map<String, Object> arguments) {

    public CapabilityInvocation {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
