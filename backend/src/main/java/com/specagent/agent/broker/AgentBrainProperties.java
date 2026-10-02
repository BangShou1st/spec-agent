package com.specagent.agent.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件名:AgentBrainProperties.java
 *
 * 用途:agent-brain 边界的配置:Python Brain 服务的地址、双向使用的
 * 共享内部密钥,以及后台 worker 的开关。所有配置默认关闭/远程,
 * 使 Stage A 不引入任何产品可见的行为变化。
 *
 * 协作:绑定 {@code spec.agent.brain} 前缀的配置项,被 broker 的
 * HTTP 客户端与 InternalModelInferenceController 读取。
 */
@Component
@ConfigurationProperties(prefix = "spec.agent.brain")
public class AgentBrainProperties implements com.specagent.common.BrainConnectionSettings {

    /** Python agent-brain 服务的基础 URL。 */
    private String baseUrl = "http://localhost:8100";

    /**
     * Brain 请求与内部推理 broker 请求都必须携带的共享内部密钥。
     * 为空时边界 fail-closed(直接禁用)。
     */
    private String internalSecret = "";

    private int connectTimeoutMs = 2000;

    /**
     * 单次 Brain 推理调用的读超时。必须覆盖 provider 可能出现的最慢真实
     * 模型往返,而不是典型耗时:在这里杀掉一次 run 会浪费一次已完成
     * (且已计费)的推理。120s 在 provider 缓慢时经常被超出(观测到
     * 128–208s),因此默认 300s,且保持可通过环境变量覆盖。
     */
    private int readTimeoutSeconds = 300;

    private final Broker broker = new Broker();

    private final Worker worker = new Worker();

    public static class Broker {
        /** 单次调用接受的总提示词字符数的硬上限。 */
        private int maxPromptChars = 200_000;
        /** 请求的最大输出 token 的硬上限。 */
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
         * 切换后的默认 profile 中,该项在 application.yml 里默认开启:
         * 接受 POST /agent-runs 的进程必须同时执行排队的 run。
         * 测试与纯 API 部署仍可显式关闭;此时 readiness 会报告缺少
         * 执行器。
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
