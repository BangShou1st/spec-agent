package com.specagent.credential;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database round-trip for encrypted credential storage. The real repository,
 * crypto and service run against PostgreSQL; only the live probe is stubbed so
 * no public network is touched.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OpenCodeCredentialIntegrationTest {

    @Autowired
    private OpenCodeCredentialService service;
    @Autowired
    private ProviderCredentialRepository repository;

    @MockBean
    private CredentialValidator validator;

    @Test
    void credentialCanBeStoredEncrypted() {
        CredentialStatus status = service.save("sk-integration-secret-ab12");

        assertThat(status.configured()).isTrue();
        assertThat(status.masked()).isEqualTo("••••ab12");
        assertThat(status.masked()).doesNotContain("sk-integration-secret-ab12");

        ProviderCredential stored = repository.findByProvider("opencode").orElseThrow();
        assertThat(stored.encryptedSecret()).isNotEqualTo("sk-integration-secret-ab12");
        assertThat(stored.encryptedSecret()).doesNotContain("sk-integration-secret-ab12");
        assertThat(stored.maskedSuffix()).isEqualTo("ab12");
    }

    @Test
    void credentialCanBeResolvedForOpenCodeGateway() {
        service.save("sk-integration-secret-ab12");

        assertThat(service.resolveOpenCode()).contains("sk-integration-secret-ab12");
    }

    @Test
    void plaintextCredentialIsNotReturnedByStatus() {
        service.save("sk-integration-secret-ab12");

        CredentialStatus status = service.status();

        assertThat(status.configured()).isTrue();
        assertThat(status.masked()).isEqualTo("••••ab12");
        assertThat(status.masked()).doesNotContain("sk-");
        assertThat(status.masked()).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    void statusIsNotConfiguredWithoutCredential() {
        CredentialStatus status = service.status();

        assertThat(status.configured()).isFalse();
        assertThat(status.masked()).isNull();
    }
}