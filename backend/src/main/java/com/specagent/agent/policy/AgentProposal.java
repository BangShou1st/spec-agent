package com.specagent.agent.policy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AgentProposal.java
 *
 * 用途:动作提案生命周期的持久化记录。跟踪提案从 PROPOSED 到
 * ACCEPTED/MODIFIED/REJECTED/EXPIRED 的全过程,使每次决策可追溯、可审计。
 * 锚点引用(anchorRefs)会被持久化,使接受操作能对照当前图状态
 * 重新校验 staleness。
 *
 * 协作:由 AgentProposalService 创建与流转,接受/拒绝入口在
 * AgentProposalController 与 ProposalAcceptanceService。
 */
public record AgentProposal(UUID id,
                            UUID runId,
                            UUID projectId,
                            UUID routeId,
                            String actionFamily,
                            Map<String, Object> payload,
                            List<String> anchorRefs,
                            ProposalStatus status,
                            UUID baseContextSnapshotId,
                            String baseContextHash,
                            String idempotencyKey,
                            Instant createdAt,
                            Instant decidedAt,
                            String decidedBy) {

    public AgentProposal {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        anchorRefs = anchorRefs == null ? List.of() : List.copyOf(anchorRefs);
    }
}
