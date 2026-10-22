package com.specagent.agent.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.specagent.agent.protocol.ActionEligibility;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:AgentRequestEnvelope.java
 *
 * 用途:Spring 发送给 Python Brain 的完整请求信封,同时服务于
 * {@code POST /v1/state-updates} 和 {@code POST /v1/decisions} 两个端点;
 * 由端点决定调用类型,信封本身结构一致。
 *
 * 约束:紧凑构造器 fail-closed 校验协议版本——v2 不允许携带
 * actionEligibility,v3/v4 必须携带 actionEligibility；仅 v4 携带 pendingDependencies。
 */
public record AgentRequestEnvelope(String protocolVersion,
                                     UUID runId,
                                     AgentEvent event,
                                     AgentInputSnapshot snapshot,
                                     List<CapabilityDescriptor> capabilities,
                                     DecisionBudget decisionBudget,
                                     @JsonInclude(JsonInclude.Include.NON_NULL)
                                     ActionEligibility actionEligibility,
                                     @JsonInclude(JsonInclude.Include.NON_NULL)
                                     PendingDependencyProjection pendingDependencies) {

    public AgentRequestEnvelope {
        boolean v2 = AgentProtocol.INPUT_PROTOCOL_VERSION_V2.equals(protocolVersion);
        boolean v3 = AgentProtocol.INPUT_PROTOCOL_VERSION_V3.equals(protocolVersion);
        boolean v4 = AgentProtocol.INPUT_PROTOCOL_VERSION_V4.equals(protocolVersion);
        if (!v2 && !v3 && !v4) {
            throw new AgentContractException(
                    "Unknown request protocol version: " + protocolVersion);
        }
        if (v2 && actionEligibility != null) {
            throw new AgentContractException("agent-input.v2 must not carry actionEligibility");
        }
        if ((v3 || v4) && actionEligibility == null) {
            throw new AgentContractException("agent-input.v3/v4 requires actionEligibility");
        }
        if (v4 != (pendingDependencies != null))
            throw new AgentContractException("Only V4 requires pendingDependencies");
        if (pendingDependencies != null) pendingDependencies.requireScope(runId, snapshot);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    public AgentRequestEnvelope(String protocolVersion, UUID runId, AgentEvent event,
            AgentInputSnapshot snapshot, List<CapabilityDescriptor> capabilities, DecisionBudget decisionBudget,
            ActionEligibility actionEligibility) {
        this(protocolVersion, runId, event, snapshot, capabilities, decisionBudget, actionEligibility, null);
    }

    public AgentRequestEnvelope(String protocolVersion,
                                UUID runId,
                                AgentEvent event,
                                AgentInputSnapshot snapshot,
                                List<CapabilityDescriptor> capabilities,
                                DecisionBudget decisionBudget) {
        this(protocolVersion, runId, event, snapshot, capabilities, decisionBudget, null);
    }
}
