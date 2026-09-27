package com.specagent.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件名:Hashes.java
 *
 * 用途:确定性的、非安全用途的哈希工具,主要用于生成内容指纹
 * (例如不可变包内容的身份标识)。
 *
 * Context 哈希只作为调试/验证辅助,不构成安全边界。
 */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256Hex(String input) {
        return sha256Hex(input == null
                ? new byte[0] : input.getBytes(StandardCharsets.UTF_8));
    }

    /** 对原始字节做 SHA-256(用于不可变包内容的身份标识)。 */
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
