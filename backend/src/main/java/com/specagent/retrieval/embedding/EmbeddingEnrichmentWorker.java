package com.specagent.retrieval.embedding;

import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runtime trigger for optional vector enrichment. It only processes derived
 * PENDING rows after canonical/index writes have completed; it never runs on
 * the snapshot construction path and provider failure leaves lexical lanes
 * available.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.retrieval.embedding.worker.enabled",
        havingValue = "true")
public class EmbeddingEnrichmentWorker {

    private static final int PROJECT_BATCH_LIMIT = 32;

    private final EmbeddingEnrichmentService enrichmentService;
    private final RetrievalEntryRepository repository;

    public EmbeddingEnrichmentWorker(EmbeddingEnrichmentService enrichmentService,
                                     RetrievalEntryRepository repository) {
        this.enrichmentService = enrichmentService;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString =
            "${spec.agent.retrieval.embedding.worker.interval-ms:5000}")
    public void tick() {
        for (UUID projectId : repository.findPendingProjectIds(PROJECT_BATCH_LIMIT)) {
            enrichmentService.enrichPending(projectId);
        }
    }
}
