package com.specagent.mcp.domain;

/**
 * 文件名:McpResource.java
 *
 * 用途:规范化的 MCP resource 原始类型。资源是可检索、带溯源的外部上下文
 * ——绝不是已确认的图谱事实,也绝不会被打平成一个 Tool。
 *
 * @param uri         资源 URI
 * @param name        资源名称
 * @param description 描述
 * @param mimeType    MIME 类型
 */
public record McpResource(
        String uri,
        String name,
        String description,
        String mimeType) {

    public McpResource {
        description = description == null ? "" : description;
        mimeType = mimeType == null ? "text/plain" : mimeType;
    }
}