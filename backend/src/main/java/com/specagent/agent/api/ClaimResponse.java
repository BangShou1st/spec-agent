package com.specagent.agent.api;

import com.specagent.workspace.patch.Claim;

import java.util.UUID;

/**
 * 文件名:ClaimResponse.java
 *
 * 用途:Answer patch 响应中的只读 claim 视图。
 *
 * 暴露内容与 Runtime grounding 的来源信息。刻意省略 Runtime 持有的
 * claim id 以保持 API 面最小;claim 绝不会被当作客户端可写对象。
 *
 * 协作:由 from(Claim) 从领域对象构造,随 patch 响应返回给前端。
 */
public record ClaimResponse(
        String kind,
        String text,
        String status,
        Double confidence,
        UUID sourceNodeId,
        UUID sourceAnswerId) {

    public static ClaimResponse from(Claim claim) {
        return new ClaimResponse(
                claim.kind().code(),
                claim.text(),
                claim.status().code(),
                claim.confidence(),
                claim.sourceNodeId(),
                claim.sourceAnswerId());
    }
}