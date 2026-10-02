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

    private com.specagent.retrieval.protocol.SharedRetrievalHost shared;
    private com.specagent.retrieval.protocol.RetrievalSourceJobs sourceJobs;
    private com.specagent.retrieval.protocol.RetrievalStore store;
    private boolean sharedPython;
    private com.specagent.retrieval.index.RetrievalSourceProjector projector;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureShared(com.specagent.retrieval.protocol.SharedRetrievalHost shared,
            com.specagent.retrieval.protocol.RetrievalSourceJobs sourceJobs,com.specagent.retrieval.protocol.RetrievalStore store,com.specagent.retrieval.index.RetrievalSourceProjector projector,
            @org.springframework.beans.factory.annotation.Value("${spec.agent.retrieval.engine:java-hybrid.v1}") String engine) {
        this.shared=shared; this.sourceJobs=sourceJobs; this.store=store; this.projector=projector; sharedPython="python-rag.v1".equals(engine);
    }
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
        if(sharedPython) {
            long tickDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
            try { var generation=store.ensureHelpGeneration(); shared.splitOneHelp(generation);
                shared.indexOneBatch(com.specagent.retrieval.protocol.CuratedHelpSources.CORPUS,generation); }
            catch(RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Help enrichment unavailable ({})",ex.getClass().getSimpleName()); }
            var projects=new java.util.LinkedHashSet<>(sourceJobs.pendingProjects(PROJECT_BATCH_LIMIT));
            projects.addAll(sourceJobs.resourceBackfillProjects(PROJECT_BATCH_LIMIT));
            projects.addAll(sourceJobs.embeddingPendingProjects(PROJECT_BATCH_LIMIT));
            for(UUID project:projects.stream().limit(PROJECT_BATCH_LIMIT).toList()) {
                if(System.nanoTime()>=tickDeadline) break; // Each leased batch is also bounded; no unbounded sweep.
                try { sourceJobs.missingResource(project).ifPresent(id->projector.rebuildSource(project,"node:"+id));
                    var generation=store.ensureProjectGeneration(project); shared.splitOneSource(project,generation); shared.indexOneBatch(project,generation); }
                catch(RuntimeException ex) {
                    // One project/provider failure does not starve others; durable leases fence late results.
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shared retrieval enrichment failed for project {} ({})",project,ex.getClass().getSimpleName());
                }
            }
            return;
        }
        for (UUID projectId : repository.findPendingProjectIds(PROJECT_BATCH_LIMIT)) {
            enrichmentService.enrichPending(projectId);
        }
    }
}
