package com.specagent.model.provider;

import com.specagent.common.Hashes;

/** Safe per-message request metadata; message text is never retained. */
public record OpenCodeMessageDiagnostics(
        int charCount,
        int byteCount,
        String sha256) {

    public OpenCodeMessageDiagnostics {
        charCount = Math.max(0, charCount);
        byteCount = Math.max(0, byteCount);
        sha256 = sha256 != null && sha256.matches("[0-9a-fA-F]{64}")
                ? sha256 : Hashes.sha256Hex("");
    }
}
