package com.specagent.credential;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Installation-wide OpenCode credential management.
 *
 * <p>A credential is stored encrypted (AES-GCM) with the plaintext secret
 * never persisted long-term and never exposed by status. Saving validates the
 * secret through the same OpenCode transport the runtime uses; a rejected
 * secret is not persisted, so a previously working credential stays intact.
 *
 * <p>This service only manages credentials. Model HTTP transport never touches
 * the database and never reads this service directly.
 */
@Service
public class OpenCodeCredentialService {

    private static final String PROVIDER = "opencode";

    private final ProviderCredentialRepository repository;
    private final CredentialCrypto crypto;
    private final CredentialValidator validator;

    public OpenCodeCredentialService(ProviderCredentialRepository repository,
                                     CredentialCrypto crypto,
                                     CredentialValidator validator) {
        this.repository = repository;
        this.crypto = crypto;
        this.validator = validator;
    }

    public CredentialStatus status() {
        return repository.findByProvider(PROVIDER)
                .map(this::toStatus)
                .orElse(new CredentialStatus(false, null));
    }

    /**
     * Validates, encrypts and persists a credential. The ciphertext replaces
     * any previously stored credential of this provider.
     */
    public CredentialStatus save(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("credential secret must not be blank");
        }
        validator.validate(secret);
        String ciphertext = crypto.encrypt(secret);
        String suffix = maskedSuffix(secret);
        repository.upsert(new ProviderCredential(
                PROVIDER, ciphertext, suffix, Instant.now(), Instant.now()));
        return new CredentialStatus(true, CredentialStatus.mask(suffix));
    }

    /**
     * Decrypts the current OpenCode credential for one model request.
     */
    public Optional<String> resolveOpenCode() {
        return repository.findByProvider(PROVIDER)
                .map(credential -> crypto.decrypt(credential.encryptedSecret()));
    }

    private CredentialStatus toStatus(ProviderCredential credential) {
        return new CredentialStatus(true, CredentialStatus.mask(credential.maskedSuffix()));
    }

    private static String maskedSuffix(String secret) {
        return secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
    }
}