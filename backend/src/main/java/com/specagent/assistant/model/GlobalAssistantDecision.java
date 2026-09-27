package com.specagent.assistant.model;

import java.util.Map;

/**
 * 文件名:GlobalAssistantDecision.java
 *
 * 用途:V2 版的判别式决策契约——模型每输出一次,恰好对应一种决策类型
 * (调用工具 / 澄清 / 导航 / 最终回答),用 kind 区分而不是堆组合开关。
 * 这是模型输出的结构化落点,后续由解析器还原、由校验器把关。
 */
public record GlobalAssistantDecision(
        DecisionKind kind,
        String assistantText,
        ToolRequest toolRequest,
        UiAction uiAction) {
    public enum DecisionKind {
        TOOL,
        CLARIFY,
        NAVIGATE,
        FINAL;
        public static DecisionKind fromCode(String code) {
            if (code == null) {
                throw new IllegalArgumentException("Decision kind must not be null");
            }
            return valueOf(code.trim().toUpperCase());
        }
    }
    public record ToolRequest(String capabilityId, Map<String, Object> arguments) {
        public ToolRequest {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }
    public record UiAction(UiDestination destination, String resourceId) {
    }
    public enum UiDestination {
        PROJECT,
        PROJECTS,
        SKILLS,
        CONNECTIONS,
        SETTINGS;
        public static UiDestination fromCode(String code) {
            if (code == null) {
                throw new IllegalArgumentException("UI destination must not be null");
            }
            return valueOf(code.trim().toUpperCase());
        }
    }
}
