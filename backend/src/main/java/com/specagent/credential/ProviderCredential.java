package com.specagent.credential;

import java.time.Instant;

/**
 * One installation-wide encrypted credential for a named provider.
 *
 * <p>Only the encrypted secret and a masked suffix may ever be persisted or
 * exposed; the plaintext secret is decrypted transiently for a request and
 * never stored long-term.
 */
public record ProviderCredential(
        String provider,
        String encryptedSecret,
        String maskedSuffix,
        Instant createdAt,
        Instant updatedAt) {

    public ProviderCredential {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (encryptedSecret == null || encryptedSecret.isBlank()) {
            throw new IllegalArgumentException("encryptedSecret is required");
        }
        if (maskedSuffix == null || maskedSuffix.isBlank()) {
            throw new IllegalArgumentException("maskedSuffix is required");
        }
    }
}