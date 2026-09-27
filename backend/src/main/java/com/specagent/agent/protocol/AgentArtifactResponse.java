package com.specagent.agent.protocol;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:AgentArtifactResponse.java
 *
 * 用途:Python Brain 返回的产物(artifact)生成响应——一份派生的、
 * 只读的交付物,只包含有依据(grounded)的内容和来源引用。
 *
 * 约束:所有 id 由 Runtime 拥有;模型只能引用请求快照允许集合内的
 * ref,此外一律拒绝。紧凑构造器按 fail-closed 方式校验协议版本。
 */
public record AgentArtifactResponse(String protocolVersion,
                                     UUID runId,
                                     ArtifactGenerationResult artifact,
                                     UsageView usage) {

    public AgentArtifactResponse {
        if (!AgentProtocol.ARTIFACT_PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new AgentContractException(
                    "Unknown artifact response protocol version: " + protocolVersion);
        }
    }

    /**
     * 一个有依据的产物章节;{@code sourceRefs} 必须非空,且每个 ref
     * 都必须落在请求快照的允许引用集合内。
     */
    public record ArtifactSection(String title, String content, List<String> sourceRefs) {
    }

    /**
     * 生成的产物主体。初期唯一支持的类型是 {@code spec_snapshot}。
     */
    public record ArtifactGenerationResult(String artifactType,
                                            List<ArtifactSection> sections,
                                            List<String> unresolvedItems) {

        public ArtifactGenerationResult {
            sections = sections == null ? List.of() : List.copyOf(sections);
            unresolvedItems = unresolvedItems == null
                    ? List.of() : List.copyOf(unresolvedItems);
        }
    }
}

