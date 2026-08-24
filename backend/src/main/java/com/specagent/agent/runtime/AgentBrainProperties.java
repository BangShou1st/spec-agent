package com.specagent.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration of the agent-brain boundary: where the Python brain lives,
 * the shared internal secret used in both directions, and the background
 * worker switches. Everything defaults to off/remote so Stage A introduces no
 * product-visible behavior change.
 */
@Component
@ConfigurationProperties(prefix = "spec.agent.brain")
public class AgentBrainProperties {

    /** Base URL of the Python agent-brain service. */
    private String baseUrl = "http://localhost:8100";

    /**
     * Shared internal secret required on brain requests and on internal
     * inference broker requests. Blank disables the boundary fail-closed.
     */
    private String internalSecret = "";

    private int connectTimeoutMs = 2000;

    private int readTimeoutSeconds = 120;

    private final Broker broker = new Broker();

    private final Worker worker = new Worker();

    public static class Broker {
        /** Hard upper bound on total prompt characters accepted per call. */
        private int maxPromptChars = 200_000;
        /** Hard upper bound on requested max output tokens. */
        private int maxOutputTokens = 32_768;

        public int getMaxPromptChars() {
            return maxPromptChars;
        }

        public void setMaxPromptChars(int maxPromptChars) {
            this.maxPromptChars = maxPromptChars;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    public static class Worker {
        /**
         * Post-cutover default is ON in the default profile via
         * application.yml: a process that accepts POST /agent-runs must also
         * execute queued runs. Tests and API-only deployments may still turn
         * it off explicitly; readiness then reports the missing executor.
         */
        private boolean enabled = true;
        private long pollIntervalMs = 2000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalSecret() {
        return internalSecret;
    }

    public void setInternalSecret(String internalSecret) {
        this.internalSecret = internalSecret;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public Broker getBroker() {
        return broker;
    }

    public Worker getWorker() {
        return worker;
    }
}
