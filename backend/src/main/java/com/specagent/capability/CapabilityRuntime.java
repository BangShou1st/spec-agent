package com.specagent.capability;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes capability invocations behind the host runtime contract.
 *
 * <p>Idempotency/retry metadata is runtime-owned: an invocation key that was
 * already executed replays the recorded result instead of re-executing side
 * effects. Unexpected adapter failures are recorded as typed FAILED results;
 * the runtime never silently retries.
 */
@Service
public class CapabilityRuntime {

    private final CapabilityRegistry registry;
    private final CapabilityInvocationRepository invocationRepository;

    public CapabilityRuntime(CapabilityRegistry registry,
                             CapabilityInvocationRepository invocationRepository) {
        this.registry = registry;
        this.invocationRepository = invocationRepository;
    }

    /**
     * Executes (or replays) one invocation. Callers must have passed policy
     * for the descriptor's side-effect class first — the runtime enforces
     * idempotency and typing, policy enforces authorization.
     */
    @Transactional
    public CapabilityResult invoke(String invocationKey,
                                   String capabilityId,
                                   UUID projectId,
                                   UUID runId,
                                   Map<String, Object> arguments) {
        Optional<CapabilityInvocationRecord> recorded =
                invocationRepository.findByInvocationKey(invocationKey);
        if (recorded.isPresent()) {
            return replayResult(recorded.orElseThrow());
        }

        CapabilityAdapter adapter = registry.findAdapter(capabilityId).orElse(null);
        CapabilityInvocation invocation = new CapabilityInvocation(
                Ids.random(), invocationKey, capabilityId, projectId, runId, arguments);
        if (adapter == null) {
            CapabilityResult failed = CapabilityResult.failed(
                    invocation.invocationId(), invocationKey, capabilityId,
                    "Unknown capability id: " + capabilityId);
            invocationRepository.insertRunning(invocation);
            invocationRepository.complete(invocation.invocationId(), failed);
            return failed;
        }

        invocationRepository.insertRunning(invocation);
        CapabilityResult result;
        try {
            result = adapter.invoke(invocation);
        } catch (RuntimeException ex) {
            result = CapabilityResult.failed(
                    invocation.invocationId(), invocationKey, capabilityId,
                    "Capability execution failed: " + ex.getClass().getSimpleName());
        }
        invocationRepository.complete(invocation.invocationId(), result);
        return result;
    }

    private CapabilityResult replayResult(CapabilityInvocationRecord record) {
        Map<String, Object> stored = record.result() == null ? Map.of() : record.result();
        return new CapabilityResult(
                record.id(),
                record.invocationKey(),
                record.capabilityId(),
                record.status() == CapabilityResult.Status.FAILED
                        ? CapabilityResult.Status.FAILED
                        : CapabilityResult.Status.REPLAYED,
                asMap(stored.get("content")),
                asStrings(stored, "sourceRefs"),
                asMap(stored.get("provenance")),
                asStrings(stored, "warnings"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private java.util.List<String> asStrings(Map<String, Object> stored, String key) {
        Object value = stored.get(key);
        return value instanceof java.util.List<?> list
                ? list.stream().map(String::valueOf).toList()
                : java.util.List.of();
    }
}
