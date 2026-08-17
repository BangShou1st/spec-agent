package com.specagent.credential;

import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bounded live probe proving that a secret authorizes OpenCode Zen.
 *
 * <p>The probe model is never hardcoded: it is chosen from the currently
 * discovered free model list, so credential validation keeps working when
 * OpenCode retires individual free models. The probe shares the exact
 * transport policy of real completion requests, so a validated key is
 * guaranteed to work with the same User-Agent and header configuration the
 * runtime uses.
 *
 * <p>Only HTTP authentication failures make the credential invalid; every other
 * failure (rate limit, server error, timeout, or no free model currently
 * available) means validation is unavailable, not that the key is wrong.
 */
@Component
public class OpenCodeCredentialValidator implements CredentialValidator {

    private final OpenCodeZenTransport transport;
    private final OpenCodeModelCatalog catalog;

    public OpenCodeCredentialValidator(OpenCodeZenTransport transport, OpenCodeModelCatalog catalog) {
        this.transport = transport;
        this.catalog = catalog;
    }

    @Override
    public void validate(String secret) {
        try {
            List<String> freeModels = catalog.listFreeModels(secret);
            if (freeModels.isEmpty()) {
                throw new ProviderValidationUnavailableError(
                        "OpenCode validation is unavailable: no free model currently available");
            }
            transport.validateCredential(secret, freeModels.get(0));
        } catch (OpenCodeModelException ex) {
            if (ex.category() == OpenCodeModelErrorCategory.AUTHENTICATION) {
                throw new InvalidProviderCredentialError("OpenCode credential is invalid");
            }
            throw new ProviderValidationUnavailableError("OpenCode validation is unavailable");
        }
    }
}