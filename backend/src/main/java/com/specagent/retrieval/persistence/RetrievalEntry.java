package com.specagent.retrieval.persistence;

import com.specagent.retrieval.MemoryAuthority;
import com.specagent.retrieval.RetrievalScope;
import com.specagent.retrieval.RetrievalSourceKind;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Canonical Java representation of one rebuildable retrieval row. */
public record RetrievalEntry(UUID id,
                             UUID projectId,
                             UUID routeId,
                             RetrievalSourceKind sourceKind,
                             UUID sourceId,
                             String sourceRef,
                             RetrievalScope scope,
                             MemoryAuthority authority,
                             String content,
                             String contentHash,
                             Map<String, Object> metadata,
                             String embeddingModel,
                             Integer embeddingDimensions,
                             String embeddingStatus,
                             Instant retractedAt) {

    public RetrievalEntry {
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        embeddingStatus = embeddingStatus == null ? "PENDING" : embeddingStatus;
    }
}
