package com.specagent.mcp.domain;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 文件名:McpResourceContent.java
 *
 * 用途:规范化的资源读取结果,附带溯源信息。第一阶段只支持文本资源;
 * 二进制内容会以带类型的失败被拒绝。
 *
 * @param uri        资源 URI
 * @param text       文本内容
 * @param mimeType   MIME 类型
 * @param provenance 溯源信息
 */
public record McpResourceContent(
        String uri,
        String text,
        String mimeType,
        Map<String, Object> provenance) {

    public McpResourceContent {
        text = text == null ? "" : text;
        mimeType = mimeType == null ? "text/plain" : mimeType;
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }

    public int byteSize() {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}