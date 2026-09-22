package com.specagent.retrieval.embedding;

import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Post-projection vector enrichment. It is deliberately separate from
 * canonical writes: provider failure changes only derived embedding status.
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

    /** Returns true only when a READY vector was persisted. */
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
