package com.specagent.agent.protocol;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:ActionProposal.java
 *
 * 用途:单个决策周期中 Brain 提出的唯一主动作提案(Brain 只提案、不执行)。
 *
 * 约束:baseContext 身份信息必须回显请求快照(snapshot id 与 hash),
 * sourceRefs 必须是快照允许引用集合的子集,payload 中绝不允许出现
 * Runtime 独有的 id。以上全部由 Java 侧 fail-closed 重新校验。
 */
public record ActionProposal(String actionFamily,
                             Map<String, Object> payload,
                             UUID baseContextSnapshotId,
                             String baseContextHash,
                             List<String> sourceRefs,
                             UUID proposalId,
                             String idempotencyKey,
                             List<String> anchorRefs) {

    public ActionProposal {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        anchorRefs = anchorRefs == null ? List.of() : List.copyOf(anchorRefs);
    }
}
