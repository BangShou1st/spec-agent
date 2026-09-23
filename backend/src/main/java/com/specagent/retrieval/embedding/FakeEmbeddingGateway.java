package com.specagent.retrieval.embedding;

import com.specagent.retrieval.EmbeddingGateway;

import com.specagent.retrieval.EmbeddingGateway.Embedding;

import com.specagent.retrieval.embedding.FakeEmbeddingGateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/** Offline deterministic embedding provider for retrieval tests and eval gates. */
@Component
@ConditionalOnProperty(name = "spec.agent.retrieval.embedding.provider",
        havingValue = "fake")
public class FakeEmbeddingGateway implements EmbeddingGateway {

    public static final String MODEL = "fake-deterministic-v1";
    public static final int DIMENSIONS = 16;

    @Override
    public Optional<Embedding> embed(String text) {
        String normalized = text == null ? "" : text;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
        float[] values = new float[DIMENSIONS];
        double norm = 0d;
        for (int index = 0; index < DIMENSIONS; index++) {
            int offset = (index * Integer.BYTES) % digest.length;
            int bits = ByteBuffer.wrap(digest, offset, Integer.BYTES).getInt();
            values[index] = (bits / (float) Integer.MAX_VALUE);
            norm += values[index] * values[index];
        }
        norm = Math.sqrt(norm);
        if (norm == 0d) {
            values[0] = 1f;
        } else {
            for (int index = 0; index < values.length; index++) {
                values[index] = (float) (values[index] / norm);
            }
        }
        return Optional.of(new Embedding(MODEL, DIMENSIONS, values));
    }
}
