package com.specagent.globalassistant.stream;

import com.specagent.globalassistant.conversation.GlobalAssistantRunEvent;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live tail + persisted replay for public run events.
 * Transport disconnect never fails or cancels the run.
 *
 * <p>Ordering contract for one run: strictly increasing sequence, no
 * duplicates, no gaps. Publish happens only after the event transaction
 * commits; subscribe replays committed events after the cursor and then
 * tails live under a per-run lock, with each subscriber tracking its own
 * last-sent sequence for dedup. Terminal events complete the emitter.
 */
@Service
public class GlobalAssistantStreamService {
    private final GlobalAssistantRunEventRepository events;
    private final GlobalAssistantRunRepository runs;
    private final Map<UUID, List<Subscriber>> live = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    public GlobalAssistantStreamService(GlobalAssistantRunEventRepository events,
            GlobalAssistantRunRepository runs) {
        this.events = events;
        this.runs = runs;
    }
    record Subscriber(UUID id, SseEmitter emitter, java.util.concurrent.atomic.AtomicInteger lastSent) {
    }
    static boolean isTerminal(String type) {
        return com.specagent.globalassistant.conversation.GlobalAssistantEventType.RUN_COMPLETED.equals(type)
                || com.specagent.globalassistant.conversation.GlobalAssistantEventType.RUN_FAILED.equals(type)
                || com.specagent.globalassistant.conversation.GlobalAssistantEventType.RUN_CANCELLED.equals(type);
    }
    private Object lockFor(UUID runId) {
        return locks.computeIfAbsent(runId, key -> new Object());
    }
    public void publish(UUID runId, GlobalAssistantRunEvent event) {
        Object lock = lockFor(runId);
        synchronized (lock) {
            List<Subscriber> subscribers = live.getOrDefault(runId, List.of());
            for (Subscriber sub : List.copyOf(subscribers)) {
                if (event.sequence() <= sub.lastSent().get()) {
                    continue;
                }
                try {
                    sub.emitter().send(eventToSse(runId, event));
                    sub.lastSent().set(event.sequence());
                } catch (IOException | IllegalStateException ex) {
                    removeSubscriber(runId, sub);
                    continue;
                }
                if (isTerminal(event.type())) {
                    completeSubscriber(runId, sub);
                }
            }
        }
    }
    public SseEmitter subscribe(UUID runId, int cursor) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber sub = new Subscriber(UUID.randomUUID(), emitter,
                new java.util.concurrent.atomic.AtomicInteger(cursor));
        emitter.onCompletion(() -> removeSubscriber(runId, sub));
        emitter.onTimeout(() -> removeSubscriber(runId, sub));
        emitter.onError(error -> removeSubscriber(runId, sub));
        Object lock = lockFor(runId);
        synchronized (lock) {
            live.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>()).add(sub);
            try {
                List<GlobalAssistantRunEvent> replay = events.findAfter(runId, sub.lastSent().get());
                for (GlobalAssistantRunEvent event : replay) {
                    if (event.sequence() <= sub.lastSent().get()) {
                        continue;
                    }
                    emitter.send(eventToSse(runId, event));
                    sub.lastSent().set(event.sequence());
                }
                if (isRunTerminal(runId)) {
                    completeSubscriber(runId, sub);
                }
            } catch (IOException ex) {
                removeSubscriber(runId, sub);
                emitter.completeWithError(ex);
            }
        }
        return emitter;
    }
    public List<Map<String, Object>> listEnvelopes(UUID runId) {
        String threadId = runs.findById(runId).map(r -> r.threadId().toString()).orElse("");
        return events.findByRun(runId).stream().map(e -> envelope(runId, threadId, e)).toList();
    }
    private void removeSubscriber(UUID runId, Subscriber sub) {
        List<Subscriber> subscribers = live.get(runId);
        if (subscribers != null) {
            subscribers.remove(sub);
        }
    }
    private void completeSubscriber(UUID runId, Subscriber sub) {
        removeSubscriber(runId, sub);
        try {
            sub.emitter().complete();
        } catch (IllegalStateException ex) {
            removeSubscriber(runId, sub);
        }
    }
    private boolean isRunTerminal(UUID runId) {
        return runs.findById(runId).map(run -> run.status().isTerminal()).orElse(true);
    }
    private SseEmitter.SseEventBuilder eventToSse(UUID runId, GlobalAssistantRunEvent event) {
        String threadId = runs.findById(runId).map(r -> r.threadId().toString()).orElse("");
        return SseEmitter.event()
                .id(String.valueOf(event.sequence()))
                .name(event.type())
                .data(envelope(runId, threadId, event));
    }
    private Map<String, Object> envelope(UUID runId, String threadId, GlobalAssistantRunEvent event) {
        return Map.of(
                "eventId", event.id().toString(),
                "runId", runId.toString(),
                "threadId", threadId,
                "type", event.type(),
                "sequence", event.sequence(),
                "createdAt", event.createdAt().toString(),
                "payload", event.payload() == null ? Map.of() : event.payload());
    }
}
