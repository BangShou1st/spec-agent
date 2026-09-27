package com.specagent.retrieval;

import com.specagent.retrieval.embedding.EmbeddingEnrichmentService;
import com.specagent.retrieval.EmbeddingGateway;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import com.specagent.retrieval.MemoryAuthority;
import com.specagent.retrieval.RetrievalScope;
import com.specagent.retrieval.RetrievalSourceKind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 文件名:EmbeddingEnrichmentServiceTest.java
 *
 * 测试目标:验证 EmbeddingEnrichmentService 的失败隔离语义——提供方
 * 不可用时仅将派生行标记为 UNAVAILABLE,提供方抛异常时仅标记为 FAILED,
 * 均不影响源数据行。
 */
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
