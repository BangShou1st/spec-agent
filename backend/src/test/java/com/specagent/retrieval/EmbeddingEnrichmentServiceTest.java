package com.specagent.retrieval;

import com.specagent.retrieval.embedding.EmbeddingEnrichmentService;
import com.specagent.retrieval.embedding.EmbeddingGateway;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import com.specagent.retrieval.api.MemoryAuthority;
import com.specagent.retrieval.api.RetrievalScope;
import com.specagent.retrieval.api.RetrievalSourceKind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmbeddingEnrichmentServiceTest {

    @Test
    void unavailableProviderMarksOnlyDerivedRowUnavailable() {
        RetrievalEntryRepository repository = mock(RetrievalEntryRepository.class);
        EmbeddingGateway gateway = text -> Optional.empty();
        EmbeddingEnrichmentService service = new EmbeddingEnrichmentService(gateway, repository);
        RetrievalEntry entry = entry();

        org.assertj.core.api.Assertions.assertThat(service.enrich(entry)).isFalse();
        verify(repository).markEmbeddingStatus(entry.id(), entry.contentHash(), "UNAVAILABLE");
    }

    @Test
    void providerFailureMarksOnlyDerivedRowFailed() {
        RetrievalEntryRepository repository = mock(RetrievalEntryRepository.class);
        EmbeddingGateway gateway = text -> {
            throw new IllegalStateException("provider down");
        };
        EmbeddingEnrichmentService service = new EmbeddingEnrichmentService(gateway, repository);
        RetrievalEntry entry = entry();

        org.assertj.core.api.Assertions.assertThat(service.enrich(entry)).isFalse();
        verify(repository).markEmbeddingStatus(entry.id(), entry.contentHash(), "FAILED");
    }

    private RetrievalEntry entry() {
        UUID projectId = UUID.randomUUID();
        return new RetrievalEntry(UUID.randomUUID(), projectId, null,
                RetrievalSourceKind.NODE, UUID.randomUUID(), "node:test",
                RetrievalScope.PROJECT, MemoryAuthority.DERIVED,
                "deterministic content", "hash", Map.of(), null, null,
                "PENDING", null);
    }
}
