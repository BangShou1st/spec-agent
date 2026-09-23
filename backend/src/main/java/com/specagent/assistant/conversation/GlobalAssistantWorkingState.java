package com.specagent.assistant.conversation;

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
        List<String> lastToolResultRefs,
        SkillDiscovery lastSkillDiscovery) {

    /**
     * The most recent read-only skill repository discovery. Persists the
     * tool-observed candidate list across turns so multi-turn skill selection
     * stays grounded in what skill.import.discover actually returned instead
     * of drifting back to model memory.
     */
    public record SkillDiscovery(String url, String ref, String suggestedPath,
                                 List<Map<String, String>> candidates) {
        public SkillDiscovery {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_VALUE_CHARS = 300;

    public GlobalAssistantWorkingState {
        candidateProjects = candidateProjects == null ? List.of() : List.copyOf(candidateProjects);
        lastToolResultRefs = lastToolResultRefs == null ? List.of() : List.copyOf(lastToolResultRefs);
    }
    public static GlobalAssistantWorkingState empty() {
        return new GlobalAssistantWorkingState(null, List.of(), null, null, List.of(), null);
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
        if (lastSkillDiscovery != null) {
            map.put("lastSkillDiscovery", skillDiscoveryToMap(lastSkillDiscovery));
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
        return new GlobalAssistantWorkingState(goal, candidates, waitingFor, lastResolved, refs,
                skillDiscoveryFromMap(map.get("lastSkillDiscovery")));
    }
    private static Map<String, Object> skillDiscoveryToMap(SkillDiscovery discovery) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (discovery.url() != null) {
            map.put("url", bounded(discovery.url()));
        }
        if (discovery.ref() != null) {
            map.put("ref", bounded(discovery.ref()));
        }
        if (discovery.suggestedPath() != null) {
            map.put("suggestedPath", bounded(discovery.suggestedPath()));
        }
        if (!discovery.candidates().isEmpty()) {
            map.put("candidates", new ArrayList<>(discovery.candidates()));
        }
        return map;
    }
    private static SkillDiscovery skillDiscoveryFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        List<Map<String, String>> candidates = new ArrayList<>();
        Object rawCandidates = m.get("candidates");
        if (rawCandidates instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    Map<String, String> copy = new LinkedHashMap<>();
                    entry.forEach((k, v) -> copy.put(String.valueOf(k),
                            v == null ? null : bounded(String.valueOf(v))));
                    if (!copy.isEmpty()) {
                        candidates.add(copy);
                    }
                }
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
        }
        String url = m.get("url") instanceof String s ? bounded(s) : null;
        if (url == null && candidates.isEmpty()) {
            return null;
        }
        String ref = m.get("ref") instanceof String s ? bounded(s) : null;
        String suggested = m.get("suggestedPath") instanceof String s ? bounded(s) : null;
        return new SkillDiscovery(url, ref, suggested, candidates);
    }
    private static String bounded(String value) {
        return value.length() <= MAX_VALUE_CHARS ? value : value.substring(0, MAX_VALUE_CHARS);
    }
}
