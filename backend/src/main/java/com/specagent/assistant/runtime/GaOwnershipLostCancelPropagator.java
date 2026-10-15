package com.specagent.assistant.runtime;

import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.runtime.execution.ExecutionToken;
import com.specagent.runtime.execution.LeaseHeartbeat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 文件名:GaOwnershipLostCancelPropagator.java
 *
 * 用途:GA 执行宿主的失权传播(design §6.4 的尽力取消)。心跳判定本进程
 * 失去某个 GA 任务的执行权时,立即向 Python 发出协作式取消请求,让远端
 * 在旧数据库租约到期<b>之前</b>就停止发起新的模型/工具调用并中止在途流。
 *
 * <p><b>identity 精确按 token 绑定(epoch + lease)。</b>Python 端的取消端点
 * 只会命中完全匹配的那次 attempt;接管方的新 epoch/lease 不受影响——旧
 * attempt 的失权绝不波及新 attempt。请求失败(网络、远端已消失)是可接受的:
 * 持久化租约到期/接管/过期扫描最终收敛,这里只是尽力缩短窗口。
 *
 * <p>心跳线程只提交有界队列，不执行数据库或网络 I/O。队列饱和时放弃尽力取消，
 * 持久化围栏仍然有效；远端取消使用 GA 绑定租约，而非调度层的认领租约。
 */
@Component
@ConditionalOnBean(GaBrainSettings.class)
public class GaOwnershipLostCancelPropagator implements LeaseHeartbeat.OwnershipLostListener {

    private static final Logger LOG = LoggerFactory.getLogger(GaOwnershipLostCancelPropagator.class);

    private final GaBrainSettings settings;
    private final GaExecutionStore executions;
    private final HttpClient client;
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                var thread = new Thread(runnable, "ga-ownership-lost-cancel");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public GaOwnershipLostCancelPropagator(GaBrainSettings settings, GaExecutionStore executions) {
        this.settings = settings;
        this.executions = executions;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public void onOwnershipLost(ExecutionToken token) {
        // 只有 GA 族的执行才需要通知 Python 执行器;其他族没有远端执行体。
        if (token.family() != com.specagent.runtime.execution.RuntimeTaskFamily.GLOBAL_ASSISTANT) {
            return;
        }
        try {
            delivery.execute(() -> cancel(token));
        } catch (java.util.concurrent.RejectedExecutionException overloadedOrStopping) {
            LOG.info("GA ownership-lost cancel skipped: delivery queue unavailable for {}", token.describe());
        }
    }

    private void cancel(ExecutionToken token) {
        URI base;
        try {
            base = URI.create(settings.getBaseUrl());
        } catch (RuntimeException malformed) {
            LOG.warn("GA ownership-lost cancel skipped: malformed brain base url");
            return;
        }
        String secret = settings.getInternalSecret();
        if (secret == null || secret.isBlank()) {
            return;
        }
        try {
            // prepare() generates an independent GA lease. Selecting by the lost epoch prevents
            // an old notification (including a queued one) from cancelling a newer attempt.
            var scope = executions.cancellationScope(token.runId(), token.epoch()).orElse(null);
            if (scope == null) return;
            HttpRequest request = HttpRequest.newBuilder(base.resolve(
                            "/internal/v1/global-assistant/executions/" + token.runId() + "/cancel"))
                    .timeout(Duration.ofSeconds(1))
                    .header("X-Spec-Agent-Internal-Token", secret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"executionEpoch\":" + token.epoch()
                                    + ",\"leaseId\":\"" + scope.leaseId() + "\"}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            LOG.info("GA ownership-lost cancel response={} for {}", response.statusCode(), token.describe());
        } catch (Exception bestEffort) {
            if (bestEffort instanceof InterruptedException) Thread.currentThread().interrupt();
            // 尽力取消:远端不可达/已消失都无害,持久化围栏仍是最终权威。
            LOG.info("GA ownership-lost cancel not delivered for {}: {}",
                    token.describe(), bestEffort.getClass().getSimpleName());
        }
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        delivery.shutdownNow();
        client.shutdownNow();
    }
}
