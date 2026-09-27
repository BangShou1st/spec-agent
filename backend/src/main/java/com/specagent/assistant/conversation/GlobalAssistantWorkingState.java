package com.specagent.assistant.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:GlobalAssistantWorkingState.java
 *
 * 用途:线程的"工作状态"——只为当前未完成任务保存有界的结构化事实
 * (目标、候选项目、等待项、最近工具结果引用等),并负责与 Map 的
 * 双向序列化。
 *
 * 角色:conversation 包的会话记忆载体。会话记忆只负责"连续性",
 * 规范事实(canonical truth)永远以工具/数据库查询结果为准。所有字段
 * 都有硬上限(候选 20 条、单值 300 字符),防止状态无限膨胀。
 */
public record GlobalAssistantWorkingState(
        String goal,
        List<Map<String, String>> candidateProjects,
        String waitingFor,
        UUID lastResolvedProjectId,
        List<String> lastToolResultRefs,
        SkillDiscovery lastSkillDiscovery) {

    /**
     * 最近一次只读技能仓库发现(skill.import.discover)的结果。把工具实际
     * 观察到的候选列表跨轮次持久化,使多轮技能选择始终锚定在真实工具
     * 返回上,而不是漂移回模型记忆。
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
