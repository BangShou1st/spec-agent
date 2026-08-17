package com.specagent.credential;

/**
 * Verifies a secret against the provider before it may be persisted.
 */
public interface CredentialValidator {

    /**
     * @throws InvalidProviderCredentialError      when the provider rejects the secret
     * @throws ProviderValidationUnavailableError  when no verdict can be reached
     */
    void validate(String secret);
}