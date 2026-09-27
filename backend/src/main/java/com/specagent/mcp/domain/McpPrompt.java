package com.specagent.mcp.domain;

/**
 * 文件名:McpPrompt.java
 *
 * 用途:规范化的 MCP prompt 原始类型。Prompt 是被发现并存储、仅供查看的资产
 * ——它们绝不会作为系统策略被自动注入,Server 端编写的指令不具有任何运行时权威。
 *
 * @param name          提示名称
 * @param description   描述
 * @param argumentCount 参数个数
 */
public record McpPrompt(
        String name,
        String description,
        int argumentCount) {

    public McpPrompt {
        description = description == null ? "" : description;
    }
}