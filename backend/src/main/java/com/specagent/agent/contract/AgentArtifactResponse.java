package com.specagent.agent.contract;

import java.util.List;
import java.util.UUID;

/**
 * The artifact generation response returned by the Python brain: a derived,
 * read-only deliverable with grounded content and source references only.
 * The runtime owns every id; the model may cite refs from the request
 * snapshot's allowed set and nothing else.
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
     * One grounded artifact section; {@code sourceRefs} must be non-empty and
     * every ref must be inside the request snapshot's allowed set.
     */
    public record ArtifactSection(String title, String content, List<String> sourceRefs) {
    }

    /**
     * The generated artifact body. Initially the only supported type is
     * {@code spec_snapshot}.
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

