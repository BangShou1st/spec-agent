package com.specagent.connection.credentials;

import java.util.UUID;

/**
 * Narrow secret storage boundary for the Connection domain. A Connection row
 * persists only a {@code credentialRef}; the plaintext secret lives behind
 * this interface, encrypted at rest, and is never written to traces, model
 * context, capability results, descriptors, or error responses.
 */
public interface SecretStore {

    /**
     * Stores a secret for one connection row under a new runtime-owned
     * reference and returns that reference. At most a masked suffix is
     * retained for status display.
     */
    String store(UUID connectionRowId, String secret);

    /**
     * Retrieves the authorized caller's secret for one credential reference.
     * Implementations must make it impossible to enumerate or exfiltrate
     * secrets through the model/API surface.
     */
    String resolve(String credentialRef);

    /** Masked display suffix, or null when the ref is unknown. */
    String maskedSuffix(String credentialRef);

    void delete(String credentialRef);
}