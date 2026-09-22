package com.specagent.retrieval.embedding;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Optional;

/** Default provider: vector retrieval is unavailable, lexical lanes remain live. */
@Component
@ConditionalOnProperty(name = "spec.agent.retrieval.embedding.provider",
        havingValue = "noop", matchIfMissing = true)
public class NoopEmbeddingGateway implements EmbeddingGateway {

    @Override
    public Optional<Embedding> embed(String text) {
        return Optional.empty();
    }
}
