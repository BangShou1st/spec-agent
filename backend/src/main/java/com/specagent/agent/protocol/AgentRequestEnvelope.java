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
 * actionEligibility,v3 必须携带 actionEligibility。
 */
public record AgentRequestEnvelope(String protocolVersion,
                                     UUID runId,
                                     AgentEvent event,
                                     AgentInputSnapshot snapshot,
                                     List<CapabilityDescriptor> capabilities,
                                     DecisionBudget decisionBudget,
                                     @JsonInclude(JsonInclude.Include.NON_NULL)
                                     ActionEligibility actionEligibility) {

    public AgentRequestEnvelope {
        boolean v2 = AgentProtocol.INPUT_PROTOCOL_VERSION_V2.equals(protocolVersion);
        boolean v3 = AgentProtocol.INPUT_PROTOCOL_VERSION_V3.equals(protocolVersion);
        if (!v2 && !v3) {
            throw new AgentContractException(
                    "Unknown request protocol version: " + protocolVersion);
        }
        if (v2 && actionEligibility != null) {
            throw new AgentContractException("agent-input.v2 must not carry actionEligibility");
        }
        if (v3 && actionEligibility == null) {
            throw new AgentContractException("agent-input.v3 requires actionEligibility");
        }
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
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
