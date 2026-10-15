package com.specagent.connection.credentials;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ConnectionCredentialCryptoTest {
    private static final String KEY_A = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String KEY_B = Base64.getEncoder().encodeToString("abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
    private final UUID org = UUID.randomUUID(), connection = UUID.randomUUID();
    private final String reference = "cred:" + connection + ":" + UUID.randomUUID();
    private final ConnectionCredentialCrypto crypto = new ConnectionCredentialCrypto(KEY_A, "");

    @Test void roundTripUsesRandomIvAndNeverPersistsPlaintext() {
        String a = crypto.encrypt("token-value", org, connection, reference);
        String b = crypto.encrypt("token-value", org, connection, reference);
        assertThat(a).startsWith("enc:mcp:v2:").doesNotContain("token-value").isNotEqualTo(b);
        assertThat(crypto.decrypt(a, org, connection, reference)).isEqualTo("token-value");
    }

    @Test void ciphertextCannotBeCopiedToAnotherOrganizationConnectionOrReference() {
        String encrypted = crypto.encrypt("token-value", org, connection, reference);
        assertThatThrownBy(() -> crypto.decrypt(encrypted, UUID.randomUUID(), connection, reference)).hasNoCause();
        assertThatThrownBy(() -> crypto.decrypt(encrypted, org, UUID.randomUUID(), reference)).hasNoCause();
        assertThatThrownBy(() -> crypto.decrypt(encrypted, org, connection, reference + ":copy")).hasNoCause();
    }

    @Test void rotationReadsOldKeyOnlyWhileFallbackIsPresent() {
        String old = crypto.encrypt("token-value", org, connection, reference);
        var rotated = new ConnectionCredentialCrypto(KEY_B, KEY_A);
        assertThat(rotated.currentEnvelope(old)).isFalse();
        String next = rotated.encrypt(rotated.decryptForMigration(old, org, connection, reference), org, connection, reference);
        assertThat(rotated.currentEnvelope(next)).isTrue();
        var withoutFallback = new ConnectionCredentialCrypto(KEY_B, "");
        assertThat(withoutFallback.decrypt(next, org, connection, reference)).isEqualTo("token-value");
        assertThatThrownBy(() -> withoutFallback.decrypt(old, org, connection, reference)).hasNoCause();
    }

    @Test void legacyCiphertextIsReadableOnlyByMigrationAndCanUseFallback() throws Exception {
        String old = legacy("legacy-token");
        assertThatThrownBy(() -> crypto.decrypt(old, org, connection, reference)).hasNoCause();
        var rotated = new ConnectionCredentialCrypto(KEY_B, KEY_A);
        String secret = rotated.decryptForMigration(old, org, connection, reference);
        assertThat(secret).isEqualTo("legacy-token");
        String scoped = rotated.encrypt(secret, org, connection, reference);
        assertThat(rotated.decrypt(scoped, org, connection, reference)).isEqualTo(secret);
    }

    @Test void malformedTruncatedAndForeignPurposeEnvelopesFailClosed() {
        for (String stored : new String[] {"plaintext-token", "AQID", "enc:v2:model-purpose", "enc:mcp:v2:unknown:AAAA"}) {
            assertThatThrownBy(() -> crypto.decryptForMigration(stored, org, connection, reference))
                    .isInstanceOf(IllegalStateException.class).hasNoCause();
        }
    }

    @Test void alteredTagFailsWithoutExposingPayload() {
        String value = crypto.encrypt("token-value", org, connection, reference);
        int separator = value.lastIndexOf(':');
        byte[] payload = Base64.getDecoder().decode(value.substring(separator + 1));
        payload[payload.length - 1] ^= 1;
        String altered = value.substring(0, separator + 1) + Base64.getEncoder().encodeToString(payload);
        assertThatThrownBy(() -> crypto.decrypt(altered, org, connection, reference))
                .hasMessage("MCP credential scope or master key does not match").hasNoCause();
    }

    @Test void missingOrMalformedPrimaryKeyNeverGeneratesALocalKey() {
        var disabled = new ConnectionCredentialCrypto("", "");
        assertThat(disabled.available()).isFalse();
        assertThatThrownBy(() -> disabled.encrypt("x", org, connection, reference)).hasMessageContaining("SPEC_AGENT_SECRET_MASTER_KEY");
        assertThatThrownBy(() -> new ConnectionCredentialCrypto("", KEY_A)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ConnectionCredentialCrypto("bad-key", "")).hasNoCause();
    }

    private static String legacy(String plaintext) throws Exception {
        byte[] iv = new byte[12]; // Deterministic fixture of the previous persisted format.
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(KEY_A), "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
    }
}
