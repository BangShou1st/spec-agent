package com.specagent.retrieval.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;

/** U0 vector wire validation only; no embedding computation, provider or index. */
public final class RetrievalVectors {
    public static final int DIMENSIONS = 1024;
    private RetrievalVectors() {}

    public static String checksum(double[] values) {
        if (values == null || values.length != DIMENSIONS)
            throw new IllegalArgumentException("retrieval vector dimensions mismatch");
        ByteBuffer bytes = ByteBuffer.allocate(DIMENSIONS * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        double squaredNorm = 0;
        for (double value : values) {
            float stored = (float) value;
            if (!Double.isFinite(value) || !Float.isFinite(stored))
                throw new IllegalArgumentException("nonfinite retrieval vector");
            squaredNorm += value * value;
            bytes.putFloat(stored);
        }
        if (Math.abs(Math.sqrt(squaredNorm) - 1.0) > 0.001)
            throw new IllegalArgumentException("retrieval vector is not normalized");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
