package com.specagent.model.provider;

/**
 * 文件名:OpenCodeDiagnosticReason.java
 *
 * 用途:OpenCode 响应无法被消费时的安全内部原因枚举。该原因只是诊断元数据;
 * 对外 API 刻意继续返回稳定的提供商无关分类 INVALID_RESPONSE。
 */
public enum OpenCodeDiagnosticReason {
    CONNECT_TIMEOUT,
    RESPONSE_TIMEOUT,
    STREAM_ERROR_EVENT,
    STREAM_MALFORMED_JSON,
    STREAM_MISSING_CHOICES,
    STREAM_MISSING_DELTA,
    STREAM_NON_TEXT_CONTENT,
    MODEL_OUTPUT_NOT_JSON,
    MODEL_OUTPUT_MISSING_ACTION,
    MODEL_OUTPUT_MISSING_OUTPUT,
    MODEL_OUTPUT_UNKNOWN_ACTION,
    MODEL_OUTPUT_TRUNCATED
}
