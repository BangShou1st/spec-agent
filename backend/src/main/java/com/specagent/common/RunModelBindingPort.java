package com.specagent.common;

import java.util.UUID;

/** Shared neutral seam for atomically freezing a Run's first model configuration fingerprint. */
public interface RunModelBindingPort {

    /** Return false for invalid input, absent/terminal runs or a different existing fingerprint. */
    boolean bindOrVerify(UUID runId, String bindingFingerprint);

    default boolean bindOrVerify(UUID runId, String fingerprint, String connectionReference) {
        return bindOrVerify(runId, fingerprint);
    }

    default java.util.Optional<String> connectionReference(UUID runId) {
        return java.util.Optional.empty();
    }

    /** Storage validates only an opaque bounded token, never provider semantics. */
    static boolean isValidConnectionReference(String reference) {
        return reference != null && reference.matches("[a-z][a-z0-9-]{0,31}:[A-Za-z0-9:._-]{1,95}");
    }

    /** Only canonical SHA-256 values can be candidates; legacy sentinels cannot verify themselves. */
    static boolean isValidFingerprint(String fingerprint) {
        return fingerprint != null && fingerprint.matches("[0-9a-f]{64}");
    }
}
