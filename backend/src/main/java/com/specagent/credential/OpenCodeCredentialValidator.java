package com.specagent.credential;

import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.springframework.stereotype.Component;

/**
 * Bounded live probe proving that a secret authorizes OpenCode Zen.
 *
 * <p>The probe shares the exact transport policy of real completion requests,
 * so a validated key is guaranteed to work with the same User-Agent and header
 * configuration the runtime uses.
 */
@Component
public class OpenCodeCredentialValidator implements CredentialValidator {

    private final OpenCodeZenTransport transport;

    public OpenCodeCredentialValidator(OpenCodeZenTransport transport) {
        this.transport = transport;
    }

    @Override
    public void validate(String secret) {
        try {
            transport.validateCredential(secret);
        } catch (OpenCodeModelException ex) {
            if (ex.category() == OpenCodeModelErrorCategory.AUTHENTICATION) {
                throw new InvalidProviderCredentialError("OpenCode credential is invalid");
            }
            throw new ProviderValidationUnavailableError("OpenCode validation is unavailable");
        }
    }
}