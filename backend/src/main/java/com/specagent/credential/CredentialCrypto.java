package com.specagent.credential;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * JDK-standard AES-GCM encryption for provider credentials.
 *
 * <p>The encryption master key must come from external environment
 * configuration ({@code SPEC_AGENT_CREDENTIAL_MASTER_KEY}) and must never be
 * committed. The key string is hashed with SHA-256 into a 256-bit AES key.
 * Every encryption draws a fresh 12-byte random IV; the stored form is
 * {@code base64(iv || ciphertext)} with a 128-bit authentication tag, so a
 * corrupted or tampered ciphertext fails decryption instead of leaking data.
 */
@Component
public class CredentialCrypto {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final byte[] keyBytes;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * @param masterKey external master key, possibly blank when encryption is
     *                  not configured; encryption then fails with
     *                  {@link CredentialCryptoError}
     */
    public CredentialCrypto(@Value("${spec.agent.credential.master-key:}") String masterKey) {
        this.keyBytes = masterKey == null || masterKey.isBlank() ? null : deriveKey(masterKey);
    }

    public boolean isAvailable() {
        return keyBytes != null;
    }

    public String encrypt(String plaintext) {
        requireAvailable();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException ex) {
            throw new CredentialCryptoError("credential encryption is unavailable", ex);
        }
    }

    public String decrypt(String ciphertext) {
        requireAvailable();
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length <= GCM_IV_LENGTH) {
                throw new CredentialCryptoError("credential encryption is unavailable");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new CredentialCryptoError("credential encryption is unavailable", ex);
        }
    }

    private void requireAvailable() {
        if (keyBytes == null) {
            throw new CredentialCryptoError("credential encryption is unavailable");
        }
    }

    private static byte[] deriveKey(String masterKey) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(masterKey.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new CredentialCryptoError("credential encryption is unavailable", ex);
        }
    }
}