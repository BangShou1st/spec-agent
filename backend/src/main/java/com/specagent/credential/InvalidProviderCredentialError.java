package com.specagent.credential;

/**
 * Raised when a credential probe proves the submitted secret is not accepted
 * by the provider (HTTP 401 / 403). The rejected secret must never be
 * persisted, so any previously stored credential stays untouched.
 */
public class InvalidProviderCredentialError extends RuntimeException {

    public InvalidProviderCredentialError(String message) {
        super(message);
    }
}