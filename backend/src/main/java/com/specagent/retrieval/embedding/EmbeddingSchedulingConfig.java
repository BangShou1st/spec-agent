package com.specagent.retrieval.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 文件名:EmbeddingSchedulingConfig.java
 *
 * 用途:开启 Spring 的 @Scheduled 调度支持,让向量增强 worker 在
 * 纯 API 部署中也能独立运行。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "spec.agent.retrieval.embedding.worker.enabled",
        havingValue = "true")
public class EmbeddingSchedulingConfig {
}
