package com.specagent.runtime.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 文件名:LocalExecutionSlots.java
 *
 * 用途:本进程实际空闲执行槽(design §6.2 第 1 步、§5.4)。
 *
 * <p>这是<b>本地</b>并发上限,不是全局限额:全局限额由数据库的
 * runtime_capacity_slots 表达。没有空闲槽时本进程不认领任何任务——绝不
 * 把任务认领到本地却排队等待执行,那会让任务在租约内空转。
 *
 * <p>槽位在"认领 → 执行 → 终态化"整个区间内持有;执行结束(无论成败)
 * 立刻释放。
 */
@Component
public class LocalExecutionSlots {

    private final Semaphore slots;
    private final int total;

    public LocalExecutionSlots(
            @Value("${spec.agent.execution.local-slots:2}") int localSlots) {
        this.total = Math.max(1, localSlots);
        this.slots = new Semaphore(this.total, true);
    }

    /** 立即取得一个空闲槽;没有空闲槽时返回 false(调用方不应认领)。 */
    public boolean tryAcquire() {
        return slots.tryAcquire();
    }

    /** 在给定时间内等待空闲槽;超时返回 false。 */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return slots.tryAcquire(timeout, unit);
    }

    public void release() {
        slots.release();
    }

    /** 当前空闲槽数(指标/健康检查用)。 */
    public int available() {
        return slots.availablePermits();
    }

    /** 本进程配置的执行槽总数。 */
    public int total() {
        return total;
    }
}
