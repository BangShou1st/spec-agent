package com.specagent.common;

import java.util.UUID;

/** Shared neutral seam for atomically freezing a Run's first model configuration fingerprint. */
public interface RunModelBindingPort {

    /** Return false when the run is absent/terminal or already has another fingerprint. */
    boolean bindOrVerify(UUID runId, String bindingFingerprint);
}
