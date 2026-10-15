package com.specagent.assistant.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件名:GlobalAssistantStreamCatchUpPoller.java
 *
 * 用途:跨实例 SSE 投递的数据库游标兜底(design §8)。
 *
 * <p>进程内 {@code publish} 只能送达发布进程自己的订阅者。多 Worker 部署下,
 * 执行一件事的 Worker 与持有对应 SSE 连接的 API 实例通常不是同一进程,因此本
 * 轮询器周期性地按每个订阅者的游标,从 {@code global_assistant_run_events} 读取
 * 已提交事件并补发——这是第一阶段的兜底机制,不依赖任何进程内 publisher。
 *
 * <p><b>无条件装配,独立于任何 Worker 开关。</b>补发的消费方是<b>持有 SSE 连接
 * 的一侧</b>——纯 API 实例(worker.enabled=false)恰恰是它的主要使用者:执行在
 * Worker A 进程内落库,本进程只能靠数据库游标把事件送达自己的订阅者。因此本
 * 轮询器不再挂靠 {@code spec.agent.brain.worker.enabled};单执行器模式下它只是
 * 多一次有界(200 条/订阅者/秒级)的数据库读取,无行为影响。
 *
 * <p>间隔刻意独立于执行轮询(默认 1 秒):事件补发的时效性直接影响用户看到的
 * 流式体验,而执行轮询的频率由调度需要决定,两者不应互相牵制。
 */
@Component
public class GlobalAssistantStreamCatchUpPoller {

    private static final Logger LOG =
            LoggerFactory.getLogger(GlobalAssistantStreamCatchUpPoller.class);

    private final GlobalAssistantStreamService streams;

    public GlobalAssistantStreamCatchUpPoller(GlobalAssistantStreamService streams) {
        this.streams = streams;
    }

    @Scheduled(fixedDelayString = "${spec.global-assistant.stream-catch-up-interval-ms:1000}")
    public void catchUp() {
        try {
            streams.scheduleCatchUpAll();
        } catch (RuntimeException ex) {
            // 补发失败绝不影响 SSE 订阅本身:下一轮会从同一个游标重试,因为
            // 游标只在发送成功后才推进。
            LOG.warn("Cross-instance SSE catch-up failed: {}", ex.getMessage());
        }
    }
}
