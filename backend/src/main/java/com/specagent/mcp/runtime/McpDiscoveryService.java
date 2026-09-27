package com.specagent.mcp.runtime;

import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpPrompt;
import com.specagent.mcp.domain.McpResource;
import com.specagent.mcp.domain.McpTool;
import com.specagent.mcp.McpDiscoveryCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:McpDiscoveryService.java
 *
 * 用途:MCP 连接的发现编排:按需执行实时发现,为每个连接持久化一份规范化缓存,
 * 重连时直接返回缓存结果。刷新(refresh)会使缓存失效并重新发现。
 */
@Service
public class McpDiscoveryService {

    private final McpConnectionRuntime connectionRuntime;
    private final McpDiscoveryCacheRepository cacheRepository;
    private final Json json;

    private static final TypeReference<List<McpTool>> TOOL_LIST =
            new TypeReference<>() {
            };
    private static final TypeReference<List<McpResource>> RESOURCE_LIST =
            new TypeReference<>() {
            };
    private static final TypeReference<List<McpPrompt>> PROMPT_LIST =
            new TypeReference<>() {
            };

    public McpDiscoveryService(McpConnectionRuntime connectionRuntime,
                               McpDiscoveryCacheRepository cacheRepository,
                               Json json) {
        this.connectionRuntime = connectionRuntime;
        this.cacheRepository = cacheRepository;
        this.json = json;
    }

    /** 实时发现(测试/刷新路径)。绝不产生远程写入。 */
    public McpDiscovery discoverLive(McpConnectionTarget connection) {
        McpDiscovery discovery = connectionRuntime.testAndDiscover(connection);
        persist(connection.rowId(), discovery);
        return discovery;
    }

    /** 有缓存就用缓存,否则实时发现(发现后写入缓存)。 */
    public McpDiscovery discover(McpConnectionTarget connection) {
        Optional<McpDiscoveryCacheRepository.CacheRow> cached =
                cacheRepository.findByConnection(connection.rowId());
        if (cached.isPresent()) {
            return fromCache(cached.get());
        }
        return discoverLive(connection);
    }

    /** 仅供管理界面使用的纯缓存读取:绝不触发实时网络发现。 */
    public Optional<McpDiscovery> findCached(UUID connectionRowId) {
        return cacheRepository.findByConnection(connectionRowId).map(this::fromCache);
    }

    public void persist(UUID connectionId, McpDiscovery discovery) {
        cacheRepository.upsert(connectionId,
                discovery.tools(), discovery.resources(), discovery.prompts(),
                fingerprint(discovery));
    }

    public void invalidate(UUID connectionId) {
        cacheRepository.delete(connectionId);
    }

    private String fingerprint(McpDiscovery discovery) {
        String tools = discovery.tools().stream()
                .map(McpTool::name).sorted().reduce("", (a, b) -> a + "|" + b);
        String resources = discovery.resources().stream()
                .map(McpResource::uri).sorted().reduce("", (a, b) -> a + "|" + b);
        String prompts = discovery.prompts().stream()
                .map(McpPrompt::name).sorted().reduce("", (a, b) -> a + "|" + b);
        return Hashes.sha256Hex(tools + "::" + resources + "::" + prompts);
    }

    private McpDiscovery fromCache(McpDiscoveryCacheRepository.CacheRow row) {
        List<McpTool> tools = json.readList(row.tools(), TOOL_LIST);
        List<McpResource> resources = json.readList(row.resources(), RESOURCE_LIST);
        List<McpPrompt> prompts = json.readList(row.prompts(), PROMPT_LIST);
        return new McpDiscovery("", "", tools, resources, prompts);
    }
}
