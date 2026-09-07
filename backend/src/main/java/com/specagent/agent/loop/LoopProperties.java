package com.specagent.agent.loop;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Autonomous continuation chain budget.
 *
 * <p>{@code maxCycles} is the total number of AgentRuns allowed in one
 * autonomous continuation chain, chain root included. It bounds execution
 * volume only; it never judges when the model should stop reasoning.
 */
@Component
@ConfigurationProperties(prefix = "spec.agent.loop")
public class LoopProperties {

    /**
     * Total runs per chain: root (cycle 0) plus continuations. A child may
     * be created exactly while {@code cycleIndex + 1 < maxCycles}, so the
     * remaining budget derives deterministically from the persisted cycle
     * index and this setting — no remaining-budget counter is stored.
     */
    private int maxCycles = 3;

    public int getMaxCycles() {
        return maxCycles;
    }

    public void setMaxCycles(int maxCycles) {
        if (maxCycles < 1) {
            throw new IllegalArgumentException(
                    "spec.agent.loop.max-cycles must be >= 1, got " + maxCycles);
        }
        this.maxCycles = maxCycles;
    }
}
