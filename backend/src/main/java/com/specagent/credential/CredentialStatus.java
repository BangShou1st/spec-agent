package com.specagent.credential;

/**
 * Public status of one provider credential. Never exposes the plaintext
 * secret; only whether it is configured and a masked suffix.
 */
public record CredentialStatus(boolean configured, String masked) {

    public CredentialStatus {
        if (!configured) {
            masked = null;
        }
    }

    /**
     * Masks a credential suffix for status display, e.g. {@code ••••abcd}.
     */
    public static String mask(String suffix) {
        return "••••" + suffix;
    }
}