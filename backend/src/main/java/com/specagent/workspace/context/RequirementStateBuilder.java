package com.specagent.workspace.context;

import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:RequirementStateBuilder.java
 *
 * 用途:通过回放回答补丁派生 {@link RequirementState}。需求状态是派生物,
 * 不是事实源——不可变的世系、回答与补丁才具权威性;可以缓存,但相同补丁的
 * 回放永远得到相同的状态。规格生成前的需求汇总即由此构建。
 */
@Service
public class RequirementStateBuilder {

    private final AnswerPatchRepository answerPatchRepository;
    private final RouteRepository routeRepository;
    private final RouteHistoryResolver routeHistoryResolver;

    public RequirementStateBuilder(AnswerPatchRepository answerPatchRepository) {
        this(answerPatchRepository, null, null);
    }

    @Autowired
    public RequirementStateBuilder(AnswerPatchRepository answerPatchRepository,
                                   RouteRepository routeRepository,
                                   RouteHistoryResolver routeHistoryResolver) {
        this.answerPatchRepository = answerPatchRepository;
        this.routeRepository = routeRepository;
        this.routeHistoryResolver = routeHistoryResolver;
    }

    /**
     * 从显式给定的有序补丁列表重建需求状态。回放相同补丁必然得到相同状态
     * (确定性、可缓存)。
     */
    public RequirementState rebuild(List<AnswerPatch> patches) {
        List<Claim> claims = new ArrayList<>();
        UUID routeId = null;
        for (AnswerPatch patch : patches) {
            if (routeId == null) {
                routeId = patch.routeId();
            }
            claims.addAll(patch.claims());
        }
        return new RequirementState(routeId, claims, Instant.now());
    }

    /**
     * 为一条 route 构建需求状态:按创建顺序加载该 route 的回答补丁并逐一回放。
     */
    public RequirementState buildForRoute(UUID projectId, UUID routeId) {
        if (routeHistoryResolver != null && routeRepository != null) {
            Route route = routeRepository.findById(routeId)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
            List<UUID> lineage = routeHistoryResolver.resolveLineage(route.tipNodeId());
            List<UUID> answerIds = routeHistoryResolver.resolveEffectiveAnswerRefs(routeId, lineage)
                    .stream().map(ref -> ref.answerId()).toList();
            List<AnswerPatch> effectivePatches = answerPatchRepository.findBySourceAnswerIds(answerIds);
            java.util.Map<UUID, AnswerPatch> byAnswer = new java.util.HashMap<>();
            for (AnswerPatch patch : effectivePatches) byAnswer.put(patch.sourceAnswerId(), patch);
            List<AnswerPatch> ordered = answerIds.stream().map(byAnswer::get).filter(java.util.Objects::nonNull).toList();
            return rebuild(ordered);
        }
        List<AnswerPatch> patches = answerPatchRepository.findByRoute(routeId);
        return rebuild(patches);
    }

    /**
     * 从上下文快照引用的补丁构建需求状态。
     *
     * 补丁严格按 {@code snapshot.includedPatchIds()} 记录的显式顺序回放。
     * 顺序即权威:相同补丁按不同顺序回放可能得到不同的需求状态,因此原样重放
     * 快照中的补丁列表,而不是从回答列表重新推导。
     */
    public RequirementState buildForContext(ContextSnapshot snapshot) {
        List<AnswerPatch> patches = answerPatchRepository.findByIdsPreservingOrder(snapshot.includedPatchIds());
        return rebuild(patches);
    }
}
