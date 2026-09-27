package com.specagent.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:RetrievedContextItem.java
 *
 * 用途:唯一暴露给 Brain 的检索结果形态,携带来源、范围、权威级别
 * 与溯源信息。运行时的排序细节被刻意省略,只保留模型理解上下文所需
 * 的元数据。
 */
public record RetrievedContextItem(String sourceRef,
                                  RetrievalSourceKind sourceKind,
                                  RetrievalScope scope,
                                  UUID originRouteId,
                                  MemoryAuthority authority,
                                  String content,
                                  Map<String, Object> location,
                                  Map<String, Object> provenance,
                                  String retrievalReason) {

    public RetrievedContextItem {
        sourceRef = sourceRef == null ? "" : sourceRef;
        content = content == null ? "" : content;
        location = immutableMap(location);
        provenance = immutableMap(provenance);
        retrievalReason = retrievalReason == null ? "" : retrievalReason;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(value));
    }
}
