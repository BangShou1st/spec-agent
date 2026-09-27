package com.specagent.mcp.domain;

import java.util.Map;

/**
 * 文件名:McpTool.java
 *
 * 用途:规范化的、与协议无关的 MCP tool 原始类型。来自外部 Server 的元数据
 * (名称/描述/Schema)一律视为不可信输入:在任何能力投影之前先在这里完成绑定与
 * 规范化;它们永远无法决定由运行时持有的策略事实(权限、副作用分类、审批)。
 *
 * @param name        工具名称
 * @param description 工具描述
 * @param annotations Server 附加的注解元数据
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> annotations) {

    public McpTool {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        description = description == null ? "" : description;
    }
}