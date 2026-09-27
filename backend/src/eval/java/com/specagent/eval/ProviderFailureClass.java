package com.specagent.eval;

/**
 * 文件名:ProviderFailureClass.java
 *
 * 用途:live 评测报告中面向 provider 的粗粒度失败分类。
 *
 * 该分类刻意与 {@link FailureClass} 分开:它只用于可靠性记账,
 * 绝不能被当作行为质量指标。
 *
 * 协作:由 {@link LiveFailureClassifier} 判定,在
 * {@link LiveStabilitySummary} 中按类计数。
 */
public enum ProviderFailureClass {
    UNAUTHORIZED_401,
    RATE_LIMIT_429,
    SERVER_5XX,
    TIMEOUT,
    BRAIN_UNAVAILABLE,
    TRANSPORT_FAILURE,
    UNKNOWN
}
