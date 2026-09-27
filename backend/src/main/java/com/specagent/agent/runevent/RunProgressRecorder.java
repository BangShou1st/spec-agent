package com.specagent.agent.runevent;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:RunProgressRecorder.java
 *
 * 用途:在某个运行上记录用户可读的 {@code PROCESS_NOTE} 进度事件。
 *
 * 约束:摘要由后端从计数、结果标签和本就面向用户的内容(例如
 * 无论如何都会持久化到图谱的 claim 文本)组合而成;prompt 原文、
 * provider 载荷与隐藏思维链依然被禁止——{@link AgentRunEvent} 的脱敏
 * 契约不变;本类是唯一组合可展示文本的地方。
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

    /** 在给定阶段内记录一条用户可读的进度说明。 */
    public void note(UUID runId, AgentRunPhase phase, String summary) {
        noteWithItems(runId, phase, summary, null);
    }

    /** 记录进度说明并附带可选的短高亮条目(有数量上限)。 */
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
