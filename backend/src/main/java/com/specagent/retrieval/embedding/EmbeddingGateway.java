package com.specagent.retrieval.embedding;

import java.util.Optional;

/** Provider boundary for optional vector enrichment. */
public interface EmbeddingGateway {

    Optional<Embedding> embed(String text);

    record Embedding(String model, int dimensions, float[] values) {
    }
}
