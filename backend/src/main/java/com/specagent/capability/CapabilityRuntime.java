package com.specagent.capability;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes capability invocations behind the host runtime contract.
 *
 * <p>Idempotency/retry metadata is runtime-owned: for one invocation key the
 * database grants execution ownership to exactly one caller (atomic claim on
 * the unique index); losers and later retries read back the recorded result
 * instead of re-executing the adapter. SUCCEEDED invocations replay their
 * recorded result, FAILED invocations replay the recorded failure without a
 * new external attempt.
 *
 * <p>Honest crash-window semantics: if the process dies after the adapter's
 * external work happened but before {@code complete} persisted the outcome,
 * the invocation stays RUNNING and later callers receive a typed
 * {@link CapabilityResult.Status#IN_PROGRESS IN_PROGRESS} state — never a
 * fabricated replay and never a silent re-execution. Exactly-once external
 * side effects cannot be guaranteed from local transactions alone; adapters
 * that touch external systems must therefore use the runtime-owned identity
 * carried by {@link CapabilityInvocation} as their downstream idempotency
 * key. Current built-in capabilities are read-only/internal and unaffected.
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
        CapabilityInvocation invocation = new CapabilityInvocation(
                Ids.random(), invocationKey, capabilityId, projectId, runId, arguments);

        if (!invocationRepository.claim(invocation)) {
            // Lost the claim race or retried a known key: the recorded row —
            // not another adapter execution — is the single source of truth.
            CapabilityInvocationRecord existing = invocationRepository
                    .findByInvocationKey(invocationKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Invocation record vanished after losing its claim race: "
                                    + invocationKey));
            return replayResult(existing);
        }

        CapabilityAdapter adapter = registry.findAdapter(capabilityId).orElse(null);
        if (adapter == null) {
            CapabilityResult failed = CapabilityResult.failed(
                    invocation.invocationId(), invocationKey, capabilityId,
                    "Unknown capability id: " + capabilityId);
            invocationRepository.complete(invocation.invocationId(), failed);
            return failed;
        }

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
        // A claimed-but-unfinished invocation (concurrent owner or crashed
        // process) surfaces as a real in-progress state: it must neither be
        // presented as a successful replay nor re-executed here.
        if (record.status() == CapabilityResult.Status.RUNNING) {
            return new CapabilityResult(
                    record.id(),
                    record.invocationKey(),
                    record.capabilityId(),
                    CapabilityResult.Status.IN_PROGRESS,
                    Map.of("reason",
                            "invocation is still running (or was interrupted before completion); "
                                    + "no result has been recorded yet"),
                    List.of(), Map.of(), List.of());
        }
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
