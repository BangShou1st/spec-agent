package com.specagent.agent.runevent;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records user-readable {@code PROCESS_NOTE} events on a run.
 *
 * <p>Summaries are composed by the backend from counts, outcome labels and
 * already user-facing content (e.g. claim texts that are persisted to the
 * graph anyway). Prompt text, provider payloads and hidden chain-of-thought
 * remain forbidden — the {@link AgentRunEvent} sanitization contract is
 * unchanged; this class is the single place that composes displayable text.
 */
@Service
public class RunProgressRecorder {

    public static final String PROCESS_NOTE_EVENT = "PROCESS_NOTE";

    static final int MAX_SUMMARY_LENGTH = 160;
    static final int MAX_ITEMS = 3;
    static final int MAX_ITEM_LENGTH = 80;

    private final AgentRunEventService eventService;

    public RunProgressRecorder(AgentRunEventService eventService) {
        this.eventService = eventService;
    }

    /** One user-readable progress note inside the given phase. */
    public void note(UUID runId, AgentRunPhase phase, String summary) {
        noteWithItems(runId, phase, summary, null);
    }

    /** A progress note plus optional short highlight items (capped). */
    public void noteWithItems(UUID runId, AgentRunPhase phase,
                              String summary, List<String> items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", truncate(summary, MAX_SUMMARY_LENGTH));
        if (items != null && !items.isEmpty()) {
            payload.put("items", items.stream()
                    .limit(MAX_ITEMS)
                    .map(item -> truncate(item, MAX_ITEM_LENGTH))
                    .toList());
        }
        eventService.append(runId, phase, PROCESS_NOTE_EVENT, payload);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
