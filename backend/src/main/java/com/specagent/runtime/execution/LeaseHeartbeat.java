package com.specagent.runtime.execution;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 文件名:LeaseHeartbeat.java
 *
 * 用途:独立有界心跳执行器(design §6.4):为正在执行的任务做条件续租。
 *
 * <p><b>绝不使用执行模型调用的线程池,也不使用共享的 {@code @Scheduled} 线程。</b>
 * 私有的单线程调度器保证:即使执行线程在长时间的模型调用/Python RPC 中阻塞
 * (甚至把共享调度线程一起饿死),续租仍然按时进行,长任务不会因为执行耗时
 * 超过租约被判为过期并诚实失败。
 *
 * <p>续租失败(条件不满足或数据库不可达)即判定失去所有权:摘除登记并置位,
 * 后续受保护写入由数据库权威拒绝——过期租约绝不复活。
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.worker.enabled", havingValue = "true")
public class LeaseHeartbeat {

    private static final Logger LOG = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final RuntimeTaskScheduler scheduler;
    private final ExecutionHeartbeatRegistry registry;
    private final RuntimeExecutionSettings settings;
    private final List<OwnershipLostListener> lostListeners;
    private ScheduledExecutorService executor;

    public LeaseHeartbeat(RuntimeTaskScheduler scheduler,
                          ExecutionHeartbeatRegistry registry,
                          RuntimeExecutionSettings settings,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          List<OwnershipLostListener> lostListeners) {
        this.scheduler = scheduler;
        this.registry = registry;
        this.settings = settings;
        this.lostListeners = lostListeners == null ? List.of() : List.copyOf(lostListeners);
    }

    @PostConstruct
    public void start() {
        long periodSeconds = heartbeatPeriodSeconds(
                settings.getHeartbeatSeconds(), settings.getLeaseSeconds());
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "runtime-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::beat, periodSeconds, periodSeconds, TimeUnit.SECONDS);
        LOG.info("Lease heartbeat started: every {}s (lease {}s)",
                periodSeconds, settings.getLeaseSeconds());
    }

    /** 心跳间隔必须显著小于租约;配置越界(>= 租约)时收敛到租约的一半。 */
    static long heartbeatPeriodSeconds(int heartbeatSeconds, int leaseSeconds) {
        long configured = Math.max(1, heartbeatSeconds);
        long lease = Math.max(1, leaseSeconds);
        return Math.max(1, Math.min(configured, Math.max(1, lease / 2)));
    }

    private void beat() {
        for (ExecutionToken token : registry.inFlightTokens()) {
            try {
                if (!scheduler.heartbeat(token)) {
                    LOG.warn("Lease heartbeat lost for {}; renewal stopped", token.describe());
                    markLostEverywhere(token);
                }
            } catch (RuntimeException ex) {
                LOG.warn("Lease heartbeat errored for {}: {}", token.describe(), ex.getMessage());
                markLostEverywhere(token);
            }
        }
    }

    /**
     * 判定失权后把事实传播到所有消费方:
     * <ul>
     *   <li>本进程内存标记({@link ExecutionHeartbeatRegistry#markOwnershipLost})
     *       ——执行链受保护写入立即拒绝;回调线程经由注册的监听器按 run scope
     *       查询,同样立即停手;</li>
     *   <li>{@link OwnershipLostListener} 监听器——GA 执行宿主据此向 Python
     *       发出<b>尽力取消</b>(identity 精确匹配本 epoch+lease,绝不影响接管后
     *       的新 attempt),让远端在旧租约到期前就停发下一步骤。</li>
     * </ul>
     * 判失权<b>绝不</b>写 runtime_tasks 的协作取消标记:恢复扫描把未清的
     * cancel_requested_at 视作取消并把接管后的新 attempt 一并终态化,旧
     * attempt 的心跳结果按协议绝不影响新 attempt。持久化层面由租约到期/
     * 接管/过期扫描兜底收敛。
     */
    private void markLostEverywhere(ExecutionToken token) {
        registry.markOwnershipLost(token);
        for (OwnershipLostListener listener : lostListeners) {
            try {
                listener.onOwnershipLost(token);
            } catch (RuntimeException listenerFailure) {
                LOG.warn("Ownership-lost listener failed for {}: {}",
                        token.describe(), listenerFailure.getMessage());
            }
        }
    }

    /**
     * 心跳失权的进程内订阅者。实现必须尽力而为、绝不阻塞心跳循环,且只能以
     * 完整 token(taskId+epoch+lease)为作用域——旧 attempt 的失权绝不波及
     * 新 attempt。
     */
    public interface OwnershipLostListener {
        void onOwnershipLost(ExecutionToken token);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
