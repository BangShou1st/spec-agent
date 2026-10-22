package com.specagent.common;

import java.util.UUID;

/** Shared neutral seam for atomically freezing a Run's first model configuration fingerprint. */
public interface RunModelBindingPort {

    /** Return false for invalid input, absent/terminal runs or a different existing fingerprint. */
    boolean bindOrVerify(UUID runId, String bindingFingerprint);

    /** Only canonical SHA-256 values can be candidates; legacy sentinels cannot verify themselves. */
    static boolean isValidFingerprint(String fingerprint) {
        return fingerprint != null && fingerprint.matches("[0-9a-f]{64}");
    }
}
