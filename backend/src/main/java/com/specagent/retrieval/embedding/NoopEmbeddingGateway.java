package com.specagent.retrieval.embedding;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Default provider: vector retrieval is unavailable, lexical lanes remain live. */
@Component
public class NoopEmbeddingGateway implements EmbeddingGateway {

    @Override
    public Optional<Embedding> embed(String text) {
        return Optional.empty();
    }
}
