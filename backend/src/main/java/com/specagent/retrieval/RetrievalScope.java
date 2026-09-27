package com.specagent.retrieval;

/**
 * 文件名:RetrievalScope.java
 *
 * 用途:候选生成之后的可见性边界枚举,决定一条检索结果
 * 属于哪个范围层级(路线/项目/资源)。
 */
public enum RetrievalScope {
    ROUTE,
    PROJECT,
    RESOURCE
}
