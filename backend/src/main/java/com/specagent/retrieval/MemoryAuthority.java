package com.specagent.retrieval;

/**
 * 文件名:MemoryAuthority.java
 *
 * 用途:记忆条目的权威级别枚举,标示一条内容可信到什么程度。
 * 权威级别独立于词法/向量相关性,是检索结果之外的正交信号。
 */
public enum MemoryAuthority {
    CONFIRMED,
    USER_AUTHORED,
    EXTERNAL_EVIDENCE,
    DERIVED,
    ASSUMED,
    UNRESOLVED,
    REJECTED
}
