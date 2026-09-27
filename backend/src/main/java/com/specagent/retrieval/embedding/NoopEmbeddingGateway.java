package com.specagent.retrieval.embedding;

import com.specagent.retrieval.EmbeddingGateway;

import com.specagent.retrieval.EmbeddingGateway.Embedding;

import com.specagent.retrieval.embedding.NoopEmbeddingGateway;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Optional;

/**
 * 文件名:NoopEmbeddingGateway.java
 *
 * 用途:默认的向量嵌入提供者——始终返回空,即向量检索不可用,
 * 词法检索通道保持完全可用。
 */
@Component
@ConditionalOnProperty(name = "spec.agent.retrieval.embedding.provider",
        havingValue = "noop", matchIfMissing = true)
public class NoopEmbeddingGateway implements EmbeddingGateway {

    @Override
    public Optional<Embedding> embed(String text) {
        return Optional.empty();
    }
}
