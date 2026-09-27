package com.specagent.agent.runtime;

import java.util.UUID;

/**
 * 文件名:ContinuationDecision.java
 *
 * 用途:续跑协调器对一个终态 run 给出的裁决。
 *
 * 只有一种裁决满足 {@code eligible} 为 true,即
 * {@link ContinuationVerdict#EXECUTED_NEW_OBSERVATION}:允许合法创建下一轮
 * 自治循环(Slice 2+)。其余所有裁决都会停靠或终结链路。决策不携带任何
 * 语义建议。
 */
public record ContinuationDecision(UUID runId,
                                    ContinuationVerdict verdict,
                                    String reason) {

    public ContinuationDecision {
        if (runId == null) {
            throw new IllegalArgumentException("Continuation decision requires a run id");
        }
        if (verdict == null) {
            throw new IllegalArgumentException("Continuation decision requires a verdict");
        }
        reason = reason == null ? "" : reason;
    }

    /**
     * 仅对一种裁决返回 true,即 {@link ContinuationVerdict#EXECUTED_NEW_OBSERVATION}:
     * 允许合法创建下一轮自治循环(Slice 2+)。其余裁决都会停靠或终结链路。
     * 可续跑性由裁决本身派生——绝不作为独立状态存储。
     */
    public boolean eligible() {
        return verdict == ContinuationVerdict.EXECUTED_NEW_OBSERVATION;
    }

    static ContinuationDecision of(UUID runId, ContinuationVerdict verdict, String reason) {
        return new ContinuationDecision(runId, verdict, reason);
    }
}
