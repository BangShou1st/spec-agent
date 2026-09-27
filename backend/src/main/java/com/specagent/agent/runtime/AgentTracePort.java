package com.specagent.agent.runtime;

import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.action.ActionEligibilityGate;
import com.specagent.agent.policy.PolicyDecision;
import java.util.UUID;

/**
 * 文件名:AgentTracePort.java
 *
 * 用途:可选的语义 trace 记录器的写侧端口(六边形架构出站端口)。
 *
 * Agent 推理层不允许依赖 trace 包:trace 实现会消费 agent 契约 DTO,
 * 自然的依赖方向是 trace → agent。本端口反转了写侧方向(agent → trace),
 * 使两个包之间保持无环。由 {@code com.specagent.agent.trace.SemanticTraceRecorder}
 * 实现;"默认关闭"等语义属于实现细节。
 */
public interface AgentTracePort {

    void captureStateUpdateInput(AgentRequestEnvelope request);

    void captureDecisionInput(AgentRequestEnvelope request);

    void captureStateUpdateOutput(AgentResponseEnvelope response);

    void captureDecisionOutput(AgentResponseEnvelope response);

    void capturePolicyDecision(UUID runId, PolicyDecision decision);

    void captureActionEligibility(UUID runId,
                                  AgentRequestEnvelope request,
                                  AgentResponseEnvelope response,
                                  ActionEligibilityGate.Assessment assessment);

    void capturePostState(UUID runId, AgentInputSnapshot postState);

    void captureFailure(UUID runId, String stage, Throwable error);
}
