package com.specagent.retrieval.persistence;

import com.specagent.retrieval.MemoryAuthority;
import com.specagent.retrieval.RetrievalScope;
import com.specagent.retrieval.RetrievalSourceKind;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:RetrievalEntry.java
 *
 * 用途:一条可重建检索行的规范化 Java 表示,对应 retrieval_entries 表的
 * 一条记录,包含来源、范围、权威级别、内容、内容哈希与向量状态等字段。
 */
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
