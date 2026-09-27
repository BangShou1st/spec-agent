package com.specagent.agent.protocol;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AgentResponseEnvelope.java
 *
 * 用途:Python Brain 返回给 Java Runtime 的完整响应信封,包含
 * grounded 状态更新或动作提案、用量统计与诊断信息。
 *
 * 约束:{@code stateUpdate} 与 {@code actionProposal} 有且仅有一个非空,
 * 与所调用的端点对应;Runtime 在任何持久化之前先校验这一点。紧凑构造器
 * 还 fail-closed 校验协议版本及 eligibility 字段与版本的匹配关系
 * (v2 不得携带 eligibility 选择字段,v3 的决策必须给出 eligibility
 * 版本与 basis hash)。
 */
public record AgentResponseEnvelope(String protocolVersion,
                                      UUID runId,
                                      StateUpdateResult stateUpdate,
                                      ObservationView observation,
                                      ActionProposal actionProposal,
                                      UsageView usage,
                                      Map<String, Object> diagnostics,
                                      String selectedEligibilityVersion,
                                      String selectedEligibilityBasisHash,
                                      List<String> eligibilityEvidenceRefs) {

    public AgentResponseEnvelope {
        boolean v2 = AgentProtocol.DECISION_PROTOCOL_VERSION_V2.equals(protocolVersion);
        boolean v3 = AgentProtocol.DECISION_PROTOCOL_VERSION_V3.equals(protocolVersion);
        if (!v2 && !v3) {
            throw new AgentContractException(
                    "Unknown response protocol version: " + protocolVersion);
        }
        eligibilityEvidenceRefs = eligibilityEvidenceRefs == null
                ? List.of() : List.copyOf(eligibilityEvidenceRefs);
        boolean carriesEligibility = selectedEligibilityVersion != null
                || selectedEligibilityBasisHash != null
                || !eligibilityEvidenceRefs.isEmpty();
        if (v2 && carriesEligibility) {
            throw new AgentContractException(
                    "agent-decision.v2 must not carry eligibility selection fields");
        }
        if (v3 && actionProposal != null
                && (selectedEligibilityVersion == null
                || selectedEligibilityBasisHash == null)) {
            throw new AgentContractException(
                    "agent-decision.v3 Decision requires eligibility version and basis hash");
        }
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public AgentResponseEnvelope(String protocolVersion,
                                 UUID runId,
                                 StateUpdateResult stateUpdate,
                                 ObservationView observation,
                                 ActionProposal actionProposal,
                                 UsageView usage,
                                 Map<String, Object> diagnostics) {
        this(protocolVersion, runId, stateUpdate, observation, actionProposal, usage,
                diagnostics, null, null, List.of());
    }
}
