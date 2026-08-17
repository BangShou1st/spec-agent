package com.specagent.credential;

/**
 * Raised when a credential probe cannot reach a verdict about the submitted
 * secret because the provider or network is temporarily unavailable. The
 * secret is not persisted and any previously stored credential stays intact.
 */
public class ProviderValidationUnavailableError extends RuntimeException {

    public ProviderValidationUnavailableError(String message) {
        super(message);
    }
}