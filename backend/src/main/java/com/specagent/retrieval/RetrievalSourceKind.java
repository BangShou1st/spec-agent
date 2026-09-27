package com.specagent.retrieval;

/**
 * 文件名:RetrievalSourceKind.java
 *
 * 用途:允许进入模型可见检索的来源类别白名单——只有列出的几类内容
 * 才可能出现在送给模型的上下文里。
 */
public enum RetrievalSourceKind {
    NODE,
    ANSWER,
    CLAIM,
    RESOURCE_CHUNK,
    CAPABILITY_OBSERVATION,
    ROUTE_SUMMARY
}
