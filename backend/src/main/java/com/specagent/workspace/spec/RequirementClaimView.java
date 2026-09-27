package com.specagent.workspace.spec;

import com.specagent.workspace.patch.Claim;

import java.util.UUID;

/**
 * 文件名:RequirementClaimView.java
 *
 * 用途:单条需求 claim 的安全读模型投影,面向前端展示。只暴露内容与基于
 * 运行时的溯源信息(source 节点/回答);运行时持有的 claim id、持久化元数据、
 * 提示词与模型负载一律不外露。claim 是派生状态,不是事实源。
 */
public record RequirementClaimView(
        String kind,
        String text,
        String status,
        Double confidence,
        UUID sourceNodeId,
        UUID sourceAnswerId) {

    public static RequirementClaimView from(Claim claim) {
        return new RequirementClaimView(
                claim.kind().code(),
                claim.text(),
                claim.status().code(),
                claim.confidence(),
                claim.sourceNodeId(),
                claim.sourceAnswerId());
    }
}