package com.specagent.retrieval.search;

import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.EmbeddingGateway;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Optional vector lane. An unavailable gateway returns no candidates. */
@Service
public class VectorCandidateRetriever {

    private final EmbeddingGateway gateway;
    private final RetrievalEntryRepository repository;

    public VectorCandidateRetriever(EmbeddingGateway gateway,
                                    RetrievalEntryRepository repository) {
        this.gateway = gateway;
        this.repository = repository;
    }

    public List<RetrievalEntry> retrieve(RetrievalQuery query) {
        List<String> routeRefs = query.scopes().size() == 1
                && query.scopes().contains(com.specagent.retrieval.RetrievalScope.ROUTE)
                ? query.routeSourceRefs().stream().toList() : List.of();
        return gateway.embed(query.queryText())
                .flatMap(embedding -> repository.vector(query.projectId(), embedding,
                        query.maxItems(), routeRefs))
                .orElseGet(List::of);
    }
}
