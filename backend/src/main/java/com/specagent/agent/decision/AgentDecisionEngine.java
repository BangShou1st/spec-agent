package com.specagent.agent.decision;

import com.specagent.agent.protocol.AgentArtifactResponse;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;

/**
 * 文件名:AgentDecisionEngine.java
 *
 * 用途:决策引擎端口(port)。一次调用代表一次完整的 Brain 操作;
 * Reflection 与 Planning 永远不会被这个边界拆成多次独立调用。
 *
 * 实现返回的响应必须已经通过 fail-closed 的
 * {@link AgentBrainResponseValidator} 校验;运行时绝不消费未经校验的
 * Brain 响应。
 */
public interface AgentDecisionEngine {

    /** 执行一次 STATE_UPDATE 循环:把回答/证据转化为有依据的 claims。 */
    AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request);

    /** 执行一次 DECISION 循环:Reflection + Planning + 主动作提案。 */
    AgentResponseEnvelope runDecision(AgentRequestEnvelope request);

    /**
     * 执行一次 ARTIFACT_GENERATION 循环:把有依据的上下文转化为派生的
     * 只读产物(目前仅支持 spec 快照)。
     */
    AgentArtifactResponse runArtifactGeneration(AgentRequestEnvelope request);
}
