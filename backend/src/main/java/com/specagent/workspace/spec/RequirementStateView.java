package com.specagent.workspace.spec;

import com.specagent.workspace.context.RequirementState;
import com.specagent.workspace.patch.Claim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:RequirementStateView.java
 *
 * 用途:只读的需求状态视图,按运行时真实的 claim 状态分组返回给前端。分组
 * 严格遵循 {@code ClaimStatus}(confirmed、assumed、unresolved、rejected),由
 * 后端派生;客户端从不自行推断状态。项目没有活跃 route 时 {@code routeId} 为
 * {@code null},视图退化为安全的空读模型,而不是凭空造一个 route。
 */
public record RequirementStateView(
        UUID projectId,
        UUID routeId,
        List<RequirementClaimView> confirmed,
        List<RequirementClaimView> assumed,
        List<RequirementClaimView> unresolved,
        List<RequirementClaimView> rejected,
        Instant builtAt) {

    /** 项目没有活跃 route 时的安全空读模型。 */
    public static RequirementStateView empty(UUID projectId) {
        return new RequirementStateView(projectId, null,
                List.of(), List.of(), List.of(), List.of(), Instant.now());
    }

    public static RequirementStateView from(UUID projectId, UUID routeId, RequirementState state) {
        List<RequirementClaimView> confirmed = new ArrayList<>();
        List<RequirementClaimView> assumed = new ArrayList<>();
        List<RequirementClaimView> unresolved = new ArrayList<>();
        List<RequirementClaimView> rejected = new ArrayList<>();
        for (Claim claim : state.claims()) {
            RequirementClaimView view = RequirementClaimView.from(claim);
            switch (claim.status()) {
                case CONFIRMED -> confirmed.add(view);
                case ASSUMED -> assumed.add(view);
                case UNRESOLVED -> unresolved.add(view);
                case REJECTED -> rejected.add(view);
            }
        }
        return new RequirementStateView(projectId, routeId,
                List.copyOf(confirmed),
                List.copyOf(assumed),
                List.copyOf(unresolved),
                List.copyOf(rejected),
                state.builtAt());
    }
}