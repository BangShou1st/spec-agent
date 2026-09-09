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
 */
@Service
public class GlobalAssistantStreamService {
    private final GlobalAssistantRunEventRepository events;
    private final GlobalAssistantRunRepository runs;
    private final Map<UUID, List<SseEmitter>> live = new ConcurrentHashMap<>();
    public GlobalAssistantStreamService(GlobalAssistantRunEventRepository events,
            GlobalAssistantRunRepository runs) {
        this.events = events;
        this.runs = runs;
    }
    public void publish(UUID runId, GlobalAssistantRunEvent event) {
        List<SseEmitter> emitters = live.getOrDefault(runId, List.of());
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(eventToSse(runId, event));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }
    public SseEmitter subscribe(UUID runId, Integer lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        live.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(e -> remove(runId, emitter));
        try {
            int cursor = lastEventId == null ? 0 : lastEventId;
            List<GlobalAssistantRunEvent> replay = events.findAfter(runId, cursor);
            for (GlobalAssistantRunEvent event : replay) {
                emitter.send(eventToSse(runId, event));
            }
            emitter.send(SseEmitter.event().comment("subscribed"));
        } catch (IOException ex) {
            remove(runId, emitter);
            emitter.completeWithError(ex);
        }
        return emitter;
    }
    public List<Map<String, Object>> listEnvelopes(UUID runId) {
        String threadId = runs.findById(runId).map(r -> r.threadId().toString()).orElse("");
        return events.findByRun(runId).stream().map(e -> envelope(runId, threadId, e)).toList();
    }
    private void remove(UUID runId, SseEmitter emitter) {
        List<SseEmitter> emitters = live.get(runId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
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
                "eventId", event.sequence(),
                "runId", runId.toString(),
                "threadId", threadId,
                "type", event.type(),
                "sequence", event.sequence(),
                "createdAt", event.createdAt().toString(),
                "payload", event.payload() == null ? Map.of() : event.payload());
    }
}
