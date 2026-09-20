package com.specagent.agent.runevent;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the whitelisted {@link RunProgressView} from a run's event rows.
 * Only sequence, phase, event type and composed summary fields are exposed;
 * raw payloads stay internal.
 */
@Service
public class RunProgressAssembler {

    /** Upper bound on returned steps so a long chain cannot bloat responses. */
    static final int MAX_STEPS = 100;

    private final AgentRunEventService eventService;

    public RunProgressAssembler(AgentRunEventService eventService) {
        this.eventService = eventService;
    }

    public RunProgressView assemble(UUID runId) {
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        if (events.isEmpty()) {
            return new RunProgressView(null, null, List.of());
        }
        List<AgentRunEvent> window = events.size() <= MAX_STEPS
                ? events
                : events.subList(events.size() - MAX_STEPS, events.size());
        List<RunProgressView.Step> steps = new ArrayList<>(window.size());
        String latestSummary = null;
        for (AgentRunEvent event : window) {
            String summary = summaryOf(event.payload());
            List<String> items = itemsOf(event.payload());
            steps.add(new RunProgressView.Step(event.sequence(), event.phase().code(),
                    event.eventType(), summary, items, event.createdAt()));
            if (summary != null) {
                latestSummary = summary;
            }
        }
        return new RunProgressView(window.get(window.size() - 1).phase().code(),
                latestSummary, List.copyOf(steps));
    }

    private static String summaryOf(Map<String, Object> payload) {
        Object summary = payload == null ? null : payload.get("summary");
        return summary instanceof String text && !text.isBlank() ? text : null;
    }

    private static List<String> itemsOf(Map<String, Object> payload) {
        Object items = payload == null ? null : payload.get("items");
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
