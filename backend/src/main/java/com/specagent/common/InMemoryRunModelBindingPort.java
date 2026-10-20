package com.specagent.common;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local binding fixture for directly constructed gateways in tests. */
public final class InMemoryRunModelBindingPort implements RunModelBindingPort {

    private final ConcurrentMap<UUID, String> bindings = new ConcurrentHashMap<>();

    @Override
    public boolean bindOrVerify(UUID runId, String fingerprint) {
        if (runId == null || !RunModelBindingPort.isValidFingerprint(fingerprint)) return false;
        String existing = bindings.putIfAbsent(runId, fingerprint);
        return existing == null || existing.equals(fingerprint);
    }
}
