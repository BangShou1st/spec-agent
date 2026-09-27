package com.specagent.agent.snapshot;

import java.util.UUID;

/**
 * 文件名:MutableSourceFingerprint.java
 *
 * 用途:冻结时刻捕获的、单个"模型可见的易变来源"的指纹。
 *
 * 用于判断已存储的提案在活体执行时是否仍然有资格:每次执行/接受之前,
 * 都会从当前权威状态重新推导每个冻结指纹并做相等比较。任一相关来源
 * 不匹配就抛 {@code STALE_CONTEXT},不做任何图变更。
 *
 * 只对冻结时刻模型实际可见的来源做指纹——无关 workspace 实体的变更
 * 绝不会把提案标成 stale。
 */
public record MutableSourceFingerprint(String sourceType,
                                       UUID sourceId,
                                       String contentHash) {
}
