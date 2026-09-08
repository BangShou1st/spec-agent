package com.specagent.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic, non-security hashing helpers.
 *
 * <p>Context hashes are a debug/verification aid, not a security boundary.
 */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256Hex(String input) {
        return sha256Hex(input == null
                ? new byte[0] : input.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 of raw bytes (used for immutable package content identity). */
    public static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input == null ? new byte[0] : input);
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
