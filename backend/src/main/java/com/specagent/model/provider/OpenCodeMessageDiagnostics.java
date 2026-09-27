package com.specagent.model.provider;

import com.specagent.common.Hashes;

/**
 * 文件名:OpenCodeMessageDiagnostics.java
 *
 * 用途:单条消息的安全请求元数据(字符数、字节数、SHA-256);绝不保留消息文本。
 */
public record OpenCodeMessageDiagnostics(
        int charCount,
        int byteCount,
        String sha256) {

    public OpenCodeMessageDiagnostics {
        charCount = Math.max(0, charCount);
        byteCount = Math.max(0, byteCount);
        sha256 = sha256 != null && sha256.matches("[0-9a-fA-F]{64}")
                ? sha256 : Hashes.sha256Hex("");
    }
}
