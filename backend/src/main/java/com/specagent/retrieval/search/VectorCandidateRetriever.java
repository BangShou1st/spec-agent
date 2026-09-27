package com.specagent.retrieval.search;

import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.EmbeddingGateway;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文件名:VectorCandidateRetriever.java
 *
 * 用途:混合检索中的可选向量通道,把查询文本嵌入成向量后按余弦距离
 * 取近邻候选。嵌入网关不可用时返回空候选,不影响其他通道。
 */
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
