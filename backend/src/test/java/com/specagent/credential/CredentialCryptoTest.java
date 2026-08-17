package com.specagent.credential;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCryptoTest {

    private static final String MASTER_KEY = "unit-test-master-key-9f2c71";

    @Test
    void encryptDecryptRoundTrips() {
        CredentialCrypto crypto = new CredentialCrypto(MASTER_KEY);

        String ciphertext = crypto.encrypt("sk-real-secret-value");

        assertThat(crypto.decrypt(ciphertext)).isEqualTo("sk-real-secret-value");
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        CredentialCrypto crypto = new CredentialCrypto(MASTER_KEY);

        String ciphertext = crypto.encrypt("sk-plaintext-must-not-leak");

        assertThat(ciphertext).doesNotContain("sk-plaintext-must-not-leak");
    }

    @Test
    void samePlaintextEncryptsToDifferentCiphertexts() {
        CredentialCrypto crypto = new CredentialCrypto(MASTER_KEY);

        String first = crypto.encrypt("sk-value");
        String second = crypto.encrypt("sk-value");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void corruptMasterKeyNeverDecryptsStoredCiphertext() {
        CredentialCrypto crypto = new CredentialCrypto(MASTER_KEY);
        String ciphertext = crypto.encrypt("sk-value");

        assertThatThrownBy(() -> new CredentialCrypto("different-master-key").decrypt(ciphertext))
                .isInstanceOf(CredentialCryptoError.class)
                .hasMessageContaining("credential encryption is unavailable");
    }

    @Test
    void corruptedCiphertextIsRejected() {
        CredentialCrypto crypto = new CredentialCrypto(MASTER_KEY);
        String ciphertext = crypto.encrypt("sk-value");

        assertThatThrownBy(() -> crypto.decrypt(ciphertext + "tampered"))
                .isInstanceOf(CredentialCryptoError.class);
        assertThatThrownBy(() -> crypto.decrypt("not-base64 at all"))
                .isInstanceOf(CredentialCryptoError.class);
    }

    @Test
    void missingMasterKeyMakesEncryptionUnavailable() {
        CredentialCrypto crypto = new CredentialCrypto("  ");

        assertThat(crypto.isAvailable()).isFalse();
        assertThatThrownBy(() -> crypto.encrypt("sk-value"))
                .isInstanceOf(CredentialCryptoError.class)
                .hasMessage("credential encryption is unavailable");
        assertThatThrownBy(() -> crypto.decrypt("whatever"))
                .isInstanceOf(CredentialCryptoError.class);
    }
}