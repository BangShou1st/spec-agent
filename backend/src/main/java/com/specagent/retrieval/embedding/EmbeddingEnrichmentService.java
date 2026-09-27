package com.specagent.retrieval.embedding;

import com.specagent.retrieval.EmbeddingGateway;

import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:EmbeddingEnrichmentService.java
 *
 * 用途:投影完成之后的向量增强服务,把待处理的检索条目交给
 * {@link EmbeddingGateway} 生成向量并回写状态。
 *
 * 它与规范化写入刻意分离:嵌入服务失败只会改变派生的 embedding 状态,
 * 不会影响既有的词法检索通道。
 */
@Service
public class EmbeddingEnrichmentService {

    private static final int BATCH_LIMIT = 512;

    private final EmbeddingGateway gateway;
    private final RetrievalEntryRepository repository;

    public EmbeddingEnrichmentService(EmbeddingGateway gateway,
                                      RetrievalEntryRepository repository) {
        this.gateway = gateway;
        this.repository = repository;
    }

    @Transactional
    public int enrichPending(UUID projectId) {
        int enriched = 0;
        List<RetrievalEntry> pending = repository.findPending(projectId, BATCH_LIMIT);
        for (RetrievalEntry entry : pending) {
            if (enrich(entry)) {
                enriched++;
            }
        }
        return enriched;
    }

    /** 只有成功持久化 READY 状态的向量时才返回 true。 */
    public boolean enrich(RetrievalEntry entry) {
        if (entry == null || entry.retractedAt() != null) {
            return false;
        }
        try {
            var embedded = gateway.embed(entry.content());
            if (embedded.isEmpty()) {
                repository.markEmbeddingStatus(entry.id(), entry.contentHash(), "UNAVAILABLE");
                return false;
            }
            EmbeddingGateway.Embedding embedding = embedded.get();
            if (embedding.values() == null
                    || embedding.values().length != embedding.dimensions()
                    || embedding.dimensions() <= 0
                    || embedding.model() == null
                    || embedding.model().isBlank()) {
                repository.markEmbeddingStatus(entry.id(), entry.contentHash(), "FAILED");
                return false;
            }
            repository.markEmbeddingReady(entry.id(), entry.contentHash(), embedding);
            return true;
        } catch (RuntimeException ex) {
            repository.markEmbeddingStatus(entry.id(), entry.contentHash(), "FAILED");
            return false;
        }
    }
}
