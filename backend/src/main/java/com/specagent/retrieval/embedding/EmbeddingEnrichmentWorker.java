package com.specagent.retrieval.embedding;

import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文件名:EmbeddingEnrichmentWorker.java
 *
 * 用途:可选向量增强的运行时触发器,定时扫描有待处理条目的项目并
 * 调用 {@link EmbeddingEnrichmentService} 批量补齐向量。
 *
 * 它只在规范化/索引写入完成之后处理派生的 PENDING 行;绝不出现在
 * 快照构建路径上,且嵌入服务失败时词法检索通道仍然可用。
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
