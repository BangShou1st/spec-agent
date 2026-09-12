package com.specagent.globalassistant.runtime;

import com.specagent.globalassistant.conversation.GlobalAssistantEventType;
import com.specagent.globalassistant.stream.GlobalAssistantRunEventService;
import java.util.Map;
import java.util.UUID;

/**
 * Coalescing publisher for transient answer-stream events of one model call.
 *
 * <p>Each generation corresponds to one provider attempt: the initial decision
 * is generation 1, a repair re-inference is generation 2. Frontend replaces its
 * draft whenever the generation changes, so a repaired answer can never
 * silently append to a failed draft. Deltas are persisted per coalesced batch
 * through the normal event path (ordering, dedup, and reconnect replay come
 * free); the authoritative assistant message is still written exactly once at
 * completion and is the only durable message. Batches flush while the provider
 * is still generating (time- or size-triggered), never after completion.
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

    /** Starts the next generation; emits RESET when a previous draft exists. */
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

    /** Accepts releasable plaintext; flushes while the provider generates. */
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

    /** Final flush at stream end; no-op when nothing is pending. */
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
