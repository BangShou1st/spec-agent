package com.specagent.assistant.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件名:GlobalAssistantObservation.java
 *
 * 用途:工具执行结果面向下一轮模型决策的观察记录——类型化、有界、脱敏。
 * 绝不包含异常堆栈、SQL、凭据、供应商原始 JSON 或思维链。
 * 字段值超过 2000 字符时截断,避免撑爆上下文。
 */
public record GlobalAssistantObservation(String capabilityId, boolean ok, Map<String, Object> data, String errorCode) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("capabilityId", capabilityId);
        map.put("ok", ok);
        if (data != null) {
            data.forEach((k, v) -> {
                String text = String.valueOf(v);
                map.put(k, text.length() <= 2000 ? v : text.substring(0, 2000) + "\u2026");
            });
        }
        if (errorCode != null) {
            map.put("errorCode", errorCode);
        }
        return map;
    }
}
