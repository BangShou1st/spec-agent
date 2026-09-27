package com.specagent.eval;

import java.util.List;

/**
 * 文件名:EvaluationProfile.java
 *
 * 用途:评测档位:确定性的 B_FAST(脚本化 Brain)是 CI 门禁,必须通过;
 * LIVE_PROVIDER(真实 provider)和 JUDGE(评审模型)档位不作为阻塞门禁,
 * 仅提供附加信号。
 *
 * 协作:记录在 {@link ObservationEnvelope} 中,随场景定义选择执行路径。
 */
public enum EvaluationProfile {
    B_FAST,
    LIVE_PROVIDER,
    JUDGE
}
