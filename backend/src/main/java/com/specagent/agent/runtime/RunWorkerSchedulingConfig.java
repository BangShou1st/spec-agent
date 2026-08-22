package com.specagent.agent.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables background polling only when the worker is explicitly switched
 * on. Stage A defaults to off so no product behavior changes; dev/docker
 * environments opt in via {@code SPEC_AGENT_BRAIN_WORKER_ENABLED=true}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "spec.agent.brain.worker.enabled", havingValue = "true")
public class RunWorkerSchedulingConfig {
}
