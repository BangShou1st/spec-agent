package com.specagent.credential;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenCodeCredentialServiceTest {

    private static final String SECRET = "sk-unit-test-secret-4x9k";

    private final ProviderCredentialRepository repository = mock(ProviderCredentialRepository.class);
    private final CredentialCrypto crypto = new CredentialCrypto("unit-test-master-key-9f2c71");
    private final CredentialValidator validator = secret -> {
    };

    private OpenCodeCredentialService service() {
        return new OpenCodeCredentialService(repository, crypto, validator);
    }

    private ProviderCredential storedCredential() {
        String ciphertext = crypto.encrypt(SECRET);
        return new ProviderCredential("opencode", ciphertext, "4x9k", Instant.now(), Instant.now());
    }

    @Test
    void credentialCanBeStoredEncrypted() {
        OpenCodeCredentialService service = service();

        CredentialStatus status = service.save(SECRET);

        ArgumentCaptor<ProviderCredential> captor = ArgumentCaptor.forClass(ProviderCredential.class);
        verify(repository).upsert(captor.capture());
        ProviderCredential stored = captor.getValue();
        assertThat(stored.encryptedSecret()).isNotEqualTo(SECRET);
        assertThat(stored.encryptedSecret()).doesNotContain(SECRET);
        assertThat(crypto.decrypt(stored.encryptedSecret())).isEqualTo(SECRET);
        assertThat(stored.maskedSuffix()).isEqualTo("4x9k");
        assertThat(status.configured()).isTrue();
        assertThat(status.masked()).isEqualTo("••••4x9k");
        assertThat(status.masked()).doesNotContain(SECRET);
    }

    @Test
    void credentialCanBeResolvedForOpenCodeGateway() {
        when(repository.findByProvider("opencode")).thenReturn(Optional.of(storedCredential()));

        Optional<String> resolved = service().resolveOpenCode();

        assertThat(resolved).contains(SECRET);
    }

    @Test
    void plaintextCredentialIsNotReturnedByStatus() {
        when(repository.findByProvider("opencode")).thenReturn(Optional.of(storedCredential()));

        CredentialStatus status = service().status();

        assertThat(status.configured()).isTrue();
        assertThat(status.masked()).isEqualTo("••••4x9k");
        assertThat(status.masked()).doesNotContain(SECRET);
        assertThat(status.masked()).doesNotContain("sk-");
        assertThat(status.masked()).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    void statusIsNotConfiguredWithoutStoredCredential() {
        when(repository.findByProvider("opencode")).thenReturn(Optional.empty());

        CredentialStatus status = service().status();

        assertThat(status.configured()).isFalse();
        assertThat(status.masked()).isNull();
    }

    @Test
    void failedReplacementKeepsExistingCiphertext() {
        when(repository.findByProvider("opencode")).thenReturn(Optional.of(storedCredential()));
        OpenCodeCredentialService service = new OpenCodeCredentialService(repository, crypto,
                secret -> {
                    throw new InvalidProviderCredentialError("OpenCode credential is invalid");
                });

        assertThatThrownBy(() -> service.save("sk-replacement-secret"))
                .isInstanceOf(InvalidProviderCredentialError.class);

        verify(repository, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unavailableValidationIsNotPersisted() {
        OpenCodeCredentialService service = new OpenCodeCredentialService(repository, crypto,
                secret -> {
                    throw new ProviderValidationUnavailableError("OpenCode validation is unavailable");
                });

        assertThatThrownBy(() -> service.save(SECRET))
                .isInstanceOf(ProviderValidationUnavailableError.class);

        verify(repository, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveRejectsBlankSecret() {
        assertThatThrownBy(() -> service().save("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().save(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}