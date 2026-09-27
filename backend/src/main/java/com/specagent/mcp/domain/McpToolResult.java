package com.specagent.mcp.domain;

import java.util.Map;

/**
 * 文件名:McpToolResult.java
 *
 * 用途:单次 MCP 工具调用的规范化结果。Provider 的输出是不可信的外部数据:
 * 在这里完成校验与限界,携带溯源信息,并且永远不会被当作已确认的图谱事实呈现。
 *
 * @param success      调用是否成功
 * @param content      结果内容
 * @param errorMessage 失败时的错误信息
 */
public record McpToolResult(
        boolean success,
        Map<String, Object> content,
        String errorMessage) {

    public McpToolResult {
        content = content == null ? Map.of() : Map.copyOf(content);
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static McpToolResult ok(Map<String, Object> content) {
        return new McpToolResult(true, content, "");
    }

    public static McpToolResult failure(String message) {
        return new McpToolResult(false, Map.of(), message);
    }
}