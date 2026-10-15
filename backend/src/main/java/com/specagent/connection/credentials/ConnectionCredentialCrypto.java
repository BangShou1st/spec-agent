package com.specagent.connection.credentials;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** MCP bearer secrets have a distinct, authenticated org/connection/reference/purpose envelope. */
@Component
public class ConnectionCredentialCrypto {
    private static final String PREFIX = "enc:mcp:v2:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final Map<String, SecretKey> keys = new LinkedHashMap<>();
    private final String primaryId;

    public ConnectionCredentialCrypto(
            @Value("${spec.agent.secret.master-key:${SPEC_AGENT_SECRET_MASTER_KEY:}}") String primary,
            @Value("${spec.agent.secret.master-key-fallback:}") String fallback) {
        if (primary == null || primary.isBlank()) {
            if (fallback != null && !fallback.isBlank())
                throw new IllegalStateException("MCP primary master key is required when fallback keys are configured");
            primaryId = null;
            return;
        }
        primaryId = addKey(primary);
        if (fallback != null && !fallback.isBlank()) {
            for (String value : fallback.split(",")) {
                if (!value.isBlank()) addKey(value);
            }
        }
    }

    public boolean available() { return primaryId != null; }

    public void requireAvailable() {
        if (!available()) throw new IllegalStateException(
                "Secret storage is disabled: SPEC_AGENT_SECRET_MASTER_KEY not configured");
    }

    public String encrypt(String secret, UUID org, UUID connection, String reference) {
        requireAvailable();
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Cannot store a blank secret");
        byte[] context = context(org, connection, reference);
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(primaryId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context);
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return PREFIX + primaryId + ":" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("MCP credential encryption failed");
        }
    }

    public String decrypt(String stored, UUID org, UUID connection, String reference) {
        requireAvailable();
        byte[] context = context(org, connection, reference);
        if (stored == null || !stored.startsWith(PREFIX)) throw invalid();
        String envelope = stored.substring(PREFIX.length());
        int separator = envelope.indexOf(':');
        if (separator <= 0) throw invalid();
        SecretKey key = keys.get(envelope.substring(0, separator));
        if (key == null) throw invalid();
        return decryptPayload(envelope.substring(separator + 1), key, context);
    }

    boolean currentEnvelope(String stored) {
        return available() && stored != null && stored.startsWith(PREFIX + primaryId + ":");
    }

    /** Only the startup migrator may read the former unscoped AES-GCM envelope. */
    String decryptForMigration(String stored, UUID org, UUID connection, String reference) {
        requireAvailable();
        if (stored != null && stored.startsWith(PREFIX)) return decrypt(stored, org, connection, reference);
        if (stored == null || stored.startsWith("enc:")) throw invalid();
        for (SecretKey key : keys.values()) {
            try { return decryptPayload(stored, key, null); }
            catch (IllegalStateException ignored) { }
        }
        throw invalid();
    }

    private static String decryptPayload(String stored, SecretKey key, byte[] context) {
        try {
            byte[] payload = Base64.getDecoder().decode(stored);
            if (payload.length < IV_BYTES + TAG_BITS / 8) throw invalid();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            if (context != null) cipher.updateAAD(context);
            return new String(cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private String addKey(String encoded) {
        try {
            byte[] raw = Base64.getDecoder().decode(encoded.strip());
            if (raw.length != 32) throw new IllegalArgumentException();
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            keys.putIfAbsent(id, new SecretKeySpec(raw, "AES"));
            return id;
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("MCP master key must be base64 of exactly 32 bytes");
        }
    }

    private static byte[] context(UUID org, UUID connection, String reference) {
        Objects.requireNonNull(org, "Credential organization is required");
        Objects.requireNonNull(connection, "Credential connection is required");
        if (reference == null || reference.isBlank() || reference.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Credential reference is required");
        return ("connection_credentials\0encrypted_secret\0MCP_BEARER\0" + org + "\0" + connection + "\0" + reference)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("MCP credential scope or master key does not match");
    }
}
