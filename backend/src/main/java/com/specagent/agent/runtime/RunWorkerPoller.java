package com.specagent.agent.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Poll loop claiming queued runs. Active only when the worker is enabled;
 * deterministic tests drive {@link RunWorker} directly instead.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.worker.enabled", havingValue = "true")
public class RunWorkerPoller {

    private final RunWorker worker;

    public RunWorkerPoller(RunWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${spec.agent.brain.worker.poll-interval-ms:2000}")
    public void poll() {
        worker.tryClaimAndExecute();
    }
}
