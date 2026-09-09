package com.specagent.mcp.runtime;

import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.connection.domain.Connection;
import com.specagent.connection.persistence.McpDiscoveryCacheRepository;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpPrompt;
import com.specagent.mcp.domain.McpResource;
import com.specagent.mcp.domain.McpTool;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Discovery orchestration for MCP connections: runs a live discovery when
 * needed, persists a normalized cache per connection, and serves cached
 * results on reconnect. Refresh invalidates the cache and re-discovers.
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

    /** Live discovery (test/refresh path). Never writes remotely. */
    public McpDiscovery discoverLive(Connection connection) {
        McpDiscovery discovery = connectionRuntime.testAndDiscover(connection);
        persist(connection.id(), discovery);
        return discovery;
    }

    /** Cached discovery if present, otherwise live discovery (then cached). */
    public McpDiscovery discover(Connection connection) {
        Optional<McpDiscoveryCacheRepository.CacheRow> cached =
                cacheRepository.findByConnection(connection.id());
        if (cached.isPresent()) {
            return fromCache(cached.get());
        }
        return discoverLive(connection);
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