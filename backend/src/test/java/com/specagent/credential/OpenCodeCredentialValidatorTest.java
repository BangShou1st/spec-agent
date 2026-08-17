package com.specagent.credential;

import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenCodeCredentialValidatorTest {

    private static final String KEY = "sk-test-key";

    private final OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
    private final OpenCodeModelCatalog catalog = mock(OpenCodeModelCatalog.class);
    private final OpenCodeCredentialValidator validator =
            new OpenCodeCredentialValidator(transport, catalog);

    @Test
    void credentialValidationUsesCurrentlyDiscoveredFreeModel() {
        when(catalog.listFreeModels(KEY)).thenReturn(List.of("current-free", "another-free"));

        validator.validate(KEY);

        // The probe model is the first currently free model, never a hardcoded one.
        verify(transport).validateCredential(KEY, "current-free");
    }

    @Test
    void noFreeModelAvailableMakesCredentialValidationUnavailable() {
        when(catalog.listFreeModels(KEY)).thenReturn(List.of());

        assertThatThrownBy(() -> validator.validate(KEY))
                .isInstanceOf(ProviderValidationUnavailableError.class);

        verify(transport, never()).validateCredential(anyString(), anyString());
    }

    @Test
    void authenticationFailureMakesCredentialInvalid() {
        when(catalog.listFreeModels(KEY)).thenReturn(List.of("current-free"));
        doThrow(new OpenCodeModelException(OpenCodeModelErrorCategory.AUTHENTICATION,
                "OpenCode request failed (HTTP 401)", 401))
                .when(transport).validateCredential(anyString(), anyString());

        assertThatThrownBy(() -> validator.validate(KEY))
                .isInstanceOf(InvalidProviderCredentialError.class);
    }

    @Test
    void otherFailuresMakeCredentialValidationUnavailable() {
        when(catalog.listFreeModels(KEY)).thenReturn(List.of("current-free"));
        doThrow(new OpenCodeModelException(OpenCodeModelErrorCategory.RATE_LIMITED,
                "OpenCode service rate limited the request", 429))
                .when(transport).validateCredential(anyString(), anyString());

        assertThatThrownBy(() -> validator.validate(KEY))
                .isInstanceOf(ProviderValidationUnavailableError.class);
    }

    @Test
    void modelListAuthenticationFailureMakesCredentialInvalid() {
        when(catalog.listFreeModels(KEY)).thenThrow(new OpenCodeModelException(
                OpenCodeModelErrorCategory.AUTHENTICATION, "OpenCode request failed (HTTP 403)", 403));

        assertThatThrownBy(() -> validator.validate(KEY))
                .isInstanceOf(InvalidProviderCredentialError.class);
    }
}