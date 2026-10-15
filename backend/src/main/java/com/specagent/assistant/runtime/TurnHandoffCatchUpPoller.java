package com.specagent.assistant.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件名:TurnHandoffCatchUpPoller.java
 *
 * 用途:跨实例的轮次续转兜底(design §6.5、§10)。
 *
 * <p><b>为什么需要它。</b>终态续转当前由 {@link TurnHandoffListener} 以
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 驱动,而那是一个<b>进程内</b>
 * Spring 事件:若 run 在 Worker A 上终态化,而 steer 的未决轮次属于本实例,监听器
 * 永远不会触发。原先唯一的兜底是启动时的 {@code recoverStranded}——也就是说,一个
 * 长期运行的实例会一直搁置这个未决轮次。
 *
 * <p>本轮询器周期性地扫描 {@code global_assistant_pending_turns} 中未被消费的
 * 行,对没有活跃 run 的会话执行续转。幂等性由既有机制保证:
 * <ul>
 *   <li>{@code tryHandoff} 在事务内锁住 thread 行,两个实例不会同时续转;</li>
 *   <li>继任 Run 由确定性 idempotency key 创建,重复尝试返回同一行;</li>
 *   <li>已 CLAIMED 的未决行复用既有继任 run,绝不产生第二个。</li>
 * </ul>
 *
 * <p>因此本类只是把"恰好一次"从"恰好一次投递"降级为"至少一次尝试 + 恰好一次
 * 效果",与 design §10 对续跑检查的要求一致。
 *
 * <p><b>无条件装配,独立于任何 Worker 开关。</b>搁置场景的本质是"run 在别的
 * 进程终态化,进程内监听器永远不触发"——纯 API 实例(worker.enabled=false)持有
 * steer 请求的未决轮次时同样会搁置。单执行器单实例部署下本扫描恒为空集,只有
 * 有界读取的开销。
 */
@Component
public class TurnHandoffCatchUpPoller {

    private static final Logger LOG = LoggerFactory.getLogger(TurnHandoffCatchUpPoller.class);

    /** 每轮处理的未决轮次上限:有界扫描,避免长时间占住调度线程。 */
    private static final int BATCH = 50;

    private final TurnHandoffService handoff;
    private final com.specagent.assistant.conversation.PendingTurnRepository pending;
    private final com.specagent.assistant.conversation.GlobalAssistantRunRepository runs;
    private final RunDispatcher dispatcher;
    /**
     * 是否由公共调度层调度 GA 任务。任务级模式下继任 Run 的调度任务已随创建
     * 事务登记,不应绕过公共调度层直接派发(那会绕过容量槽与范围锁)。
     */
    private final boolean taskLevelScheduling;

    public TurnHandoffCatchUpPoller(
            TurnHandoffService handoff,
            com.specagent.assistant.conversation.PendingTurnRepository pending,
            com.specagent.assistant.conversation.GlobalAssistantRunRepository runs,
            RunDispatcher dispatcher,
            com.specagent.runtime.execution.ExecutionOwnershipMode ownershipMode) {
        this.handoff = handoff;
        this.pending = pending;
        this.runs = runs;
        this.dispatcher = dispatcher;
        this.taskLevelScheduling = ownershipMode != null && ownershipMode.taskLevel();
    }

    @Scheduled(fixedDelayString = "${spec.global-assistant.handoff-catch-up-interval-ms:3000}")
    public void catchUp() {
        java.util.List<com.specagent.assistant.conversation.PendingTurn> stranded;
        try {
            stranded = pending.findStrandedPending();
        } catch (RuntimeException ex) {
            LOG.warn("Turn-handoff catch-up scan failed: {}", ex.getMessage());
            return;
        }
        int handled = 0;
        for (var turn : stranded) {
            if (handled >= BATCH) {
                break;
            }
            try {
                // 有活跃 run 的会话不能续转:那条 run 还在执行,它会自己收尾。
                if (runs.findActiveByThread(turn.threadId()).isPresent()) {
                    continue;
                }
                var successor = handoff.tryHandoff(turn.threadId());
                if (successor.isPresent()) {
                    var s = successor.get();
                    // 派发只在单执行器模式下有意义(低延迟快速通道);任务级模式下
                    // 继任 run 的调度任务已在创建事务里登记,由公共调度层认领。
                    if (!taskLevelScheduling) {
                        dispatcher.dispatch(s.run().threadId(), s.run().id(), s.message(),
                                s.uiRequest());
                    }
                    handled++;
                }
            } catch (RuntimeException ex) {
                LOG.warn("Turn-handoff catch-up failed for thread {}: {}",
                        turn.threadId(), ex.getMessage());
            }
        }
        if (handled > 0) {
            LOG.info("Turn-handoff catch-up created {} successor run(s) that no in-process "
                    + "terminal event could deliver", handled);
        }
    }
}
