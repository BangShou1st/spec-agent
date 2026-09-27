package com.specagent.mcp.domain;

import java.util.List;
import java.util.Map;

/**
 * 文件名:McpDiscovery.java
 *
 * 用途:一个 MCP 连接的完整、规范化发现结果。这是唯一允许离开 {@code mcp}
 * 模块的数据形态——SDK 类型绝不跨越该边界。
 *
 * @param serverInfo      Server 的信息描述
 * @param protocolVersion 协议版本
 * @param tools           发现的工具列表
 * @param resources       发现的资源列表
 * @param prompts         发现的提示列表
 */
public record McpDiscovery(
        String serverInfo,
        String protocolVersion,
        List<McpTool> tools,
        List<McpResource> resources,
        List<McpPrompt> prompts) {

    public McpDiscovery {
        tools = tools == null ? List.of() : List.copyOf(tools);
        resources = resources == null ? List.of() : List.copyOf(resources);
        prompts = prompts == null ? List.of() : List.copyOf(prompts);
        serverInfo = serverInfo == null ? "" : serverInfo;
        protocolVersion = protocolVersion == null ? "" : protocolVersion;
    }
}