package com.specagent.assistant.runtime;

import com.specagent.assistant.conversation.GlobalAssistantEventType;
import com.specagent.assistant.runtime.GlobalAssistantRunEventService;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AnswerStreamPublisher.java
 *
 * 用途:对单次模型调用的瞬态答案流事件做合并批量发布的发布器。
 *
 * 每个 generation 对应一次供应商尝试:初始决策是 generation 1,
 * 修复重推理是 generation 2。前端在 generation 变化时会整体替换草稿,
 * 因此修复后的答案绝不会悄悄拼接到失败的草稿后面。增量文本按合并后的
 * 批次走普通事件链路持久化(顺序、去重与断线重连回放都是现成的);
 * 权威的助手消息仍在完成时恰好写一次,也是唯一的持久消息。
 * 批次在供应商仍在生成时按时间或大小触发刷出,完成后不再刷。
 */
final class AnswerStreamPublisher {

    private static final long FLUSH_INTERVAL_MILLIS = 150;
    private static final int FLUSH_MIN_CHARS = 64;

    private final GlobalAssistantRunEventService runEvents;
    private final UUID runId;
    private final long runStartNanos;
    private int generation = 0;
    private boolean generationStarted = false;
    private final StringBuilder pending = new StringBuilder();
    private long lastFlushNanos = 0;
    private long firstDeltaNanos = -1;
    private int deltaCount = 0;

    AnswerStreamPublisher(GlobalAssistantRunEventService runEvents, UUID runId, long runStartNanos) {
        this.runEvents = runEvents;
        this.runId = runId;
        this.runStartNanos = runStartNanos;
    }

    /** 开启下一个 generation;若已有旧草稿则发出 RESET 事件。 */
    int nextGeneration() {
        finish();
        if (generationStarted) {
            int superseded = generation;
            generation += 1;
            generationStarted = false;
            runEvents.append(runId, GlobalAssistantEventType.ANSWER_STREAM_RESET,
                    Map.of("supersededGeneration", superseded, "generation", generation));
        } else {
            generation += 1;
        }
        return generation;
    }

    /** 接收可释放的明文;趁供应商还在生成时就批量刷出。 */
    void accept(String text) {
        if (text == null || text.isEmpty()) return;
        if (!generationStarted) {
            generationStarted = true;
            runEvents.append(runId, GlobalAssistantEventType.ANSWER_STREAM_STARTED,
                    Map.of("generation", generation));
        }
        pending.append(text);
        long now = System.nanoTime();
        if (pending.length() >= FLUSH_MIN_CHARS
                || lastFlushNanos == 0
                || (now - lastFlushNanos) / 1_000_000 >= FLUSH_INTERVAL_MILLIS) {
            flush();
        }
    }

    /** 流结束时的最终刷出;没有积压内容时是空操作。 */
    void finish() {
        if (pending.length() > 0) {
            flush();
        }
    }

    private void flush() {
        String text = pending.toString();
        pending.setLength(0);
        if (text.isEmpty()) return;
        long now = System.nanoTime();
        if (firstDeltaNanos < 0) {
            firstDeltaNanos = now;
        }
        lastFlushNanos = now;
        deltaCount += 1;
        runEvents.append(runId, GlobalAssistantEventType.ANSWER_DELTA,
                Map.of("generation", generation, "text", text, "transient", true));
    }

    long firstDeltaMillisSinceRunStart() {
        if (firstDeltaNanos < 0) return -1;
        return (firstDeltaNanos - runStartNanos) / 1_000_000;
    }

    int deltaCount() {
        return deltaCount;
    }

    int generation() {
        return generation;
    }
}
