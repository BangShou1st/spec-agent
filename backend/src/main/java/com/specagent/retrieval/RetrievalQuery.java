package com.specagent.retrieval;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:RetrievalQuery.java
 *
 * 用途:运行时构建的检索查询请求,描述 Brain 想要什么上下文
 * (范围、查询文本、条数与字符上限、来源过滤等)。
 *
 * Brain 永远不直接构造数据库/向量查询,只提交这份领域内的查询对象。
 */
public record RetrievalQuery(UUID projectId,
                             UUID routeId,
                             UUID anchorNodeId,
                             String operation,
                             String queryText,
                             List<RetrievalScope> scopes,
                             int maxItems,
                             int maxChars,
                             Set<String> routeSourceRefs,
                             Set<UUID> graphNodeIds) {

    public RetrievalQuery {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        queryText = queryText == null ? "" : queryText.strip();
        scopes = scopes == null || scopes.isEmpty()
                ? List.of(RetrievalScope.ROUTE, RetrievalScope.PROJECT, RetrievalScope.RESOURCE)
                : List.copyOf(scopes);
        maxItems = Math.max(0, Math.min(maxItems, 64));
        maxChars = Math.max(0, Math.min(maxChars, 50_000));
        routeSourceRefs = routeSourceRefs == null ? Set.of() : Set.copyOf(routeSourceRefs);
        graphNodeIds = graphNodeIds == null ? Set.of() : Set.copyOf(graphNodeIds);
    }
}
