package com.specagent.credential;

/**
 * Raised when credential encryption or decryption cannot be performed, for
 * example when the master key is not configured or a ciphertext is corrupted.
 */
public class CredentialCryptoError extends RuntimeException {

    public CredentialCryptoError(String message) {
        super(message);
    }

    public CredentialCryptoError(String message, Throwable cause) {
        super(message, cause);
    }
}