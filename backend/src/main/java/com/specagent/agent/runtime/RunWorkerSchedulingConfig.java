package com.specagent.agent.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables background polling only when the worker is explicitly switched
 * on. Post-cutover the default profile enables it via application.yml so a
 * process accepting agent runs always has an executor. Tests and API-only
 * deployments may still disable it via
 * {@code SPEC_AGENT_BRAIN_WORKER_ENABLED=false}; the health endpoint then
 * reports the missing executor instead of silently leaving runs queued.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "spec.agent.brain.worker.enabled", havingValue = "true")
public class RunWorkerSchedulingConfig {
}
