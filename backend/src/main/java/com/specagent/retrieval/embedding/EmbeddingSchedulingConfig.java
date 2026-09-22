package com.specagent.retrieval.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the independent enrichment scheduler in API-only deployments too. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "spec.agent.retrieval.embedding.worker.enabled",
        havingValue = "true")
public class EmbeddingSchedulingConfig {
}
