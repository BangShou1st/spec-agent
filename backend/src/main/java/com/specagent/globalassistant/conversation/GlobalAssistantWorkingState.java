package com.specagent.globalassistant.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded structured facts for the unfinished task only.
 * Conversation memory is continuity; canonical truth always comes from tools/DB.
 */
public record GlobalAssistantWorkingState(
        String goal,
        List<Map<String, String>> candidateProjects,
        String waitingFor,
        UUID lastResolvedProjectId,
        List<String> lastToolResultRefs) {
    public GlobalAssistantWorkingState {
        candidateProjects = candidateProjects == null ? List.of() : List.copyOf(candidateProjects);
        lastToolResultRefs = lastToolResultRefs == null ? List.of() : List.copyOf(lastToolResultRefs);
    }
    public static GlobalAssistantWorkingState empty() {
        return new GlobalAssistantWorkingState(null, List.of(), null, null, List.of());
    }
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (goal != null) {
            map.put("goal", goal);
        }
        if (!candidateProjects.isEmpty()) {
            map.put("candidateProjects", new ArrayList<>(candidateProjects));
        }
        if (waitingFor != null) {
            map.put("waitingFor", waitingFor);
        }
        if (lastResolvedProjectId != null) {
            map.put("lastResolvedProjectId", lastResolvedProjectId.toString());
        }
        if (!lastToolResultRefs.isEmpty()) {
            map.put("lastToolResultRefs", new ArrayList<>(lastToolResultRefs));
        }
        return map;
    }
    @SuppressWarnings("unchecked")
    public static GlobalAssistantWorkingState fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return empty();
        }
        String goal = map.get("goal") instanceof String s ? s : null;
        List<Map<String, String>> candidates = new ArrayList<>();
        Object rawCandidates = map.get("candidateProjects");
        if (rawCandidates instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    m.forEach((k, v) -> entry.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
                    if (entry.size() <= 4 && entry.size() > 0) {
                        candidates.add(entry);
                    }
                }
                if (candidates.size() >= 10) {
                    break;
                }
            }
        }
        String waitingFor = map.get("waitingFor") instanceof String s ? s : null;
        UUID lastResolved = null;
        Object rawResolved = map.get("lastResolvedProjectId");
        if (rawResolved instanceof String s && !s.isBlank()) {
            try {
                lastResolved = UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                lastResolved = null;
            }
        }
        List<String> refs = new ArrayList<>();
        Object rawRefs = map.get("lastToolResultRefs");
        if (rawRefs instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    refs.add(String.valueOf(item));
                }
                if (refs.size() >= 20) {
                    break;
                }
            }
        }
        return new GlobalAssistantWorkingState(goal, candidates, waitingFor, lastResolved, refs);
    }
}
