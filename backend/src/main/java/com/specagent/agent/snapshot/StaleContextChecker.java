package com.specagent.agent.snapshot;

import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.StaleProposalException;

import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.snapshot.AgentInputProjectionRepository;
import com.specagent.agent.snapshot.MutableSourceFingerprint;
import com.specagent.agent.snapshot.MutableSourceFingerprinter;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextRelation;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.graph.NodeRelation;
import com.specagent.workspace.graph.NodeRelationRepository;
import com.specagent.workspace.graph.NodeRelationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文件名:StaleContextChecker.java
 *
 * 用途:在执行持久化变更之前,校验动作提案的冻结输入是否仍具备有效的
 * 活体前置条件(stale 检查)。
 *
 * snapshot id/哈希证明提案与冻结输入的身份一致;易变节点正文/存活状态
 * 以及有界的 NODE_QUERY 语义关系集合,则独立对照权威活状态逐一核验。
 * 只读响应在 workspace 发生漂移之后仍可从原始冻结 payload 回放。
 *
 * 协作:由动作执行链路(ActionEligibilityValidator/ProposalActionExecutor)
 * 在消费提案前调用,任何前置条件不满足即抛 StaleProposalException。
 */
@Component
public class StaleContextChecker {

    private static final Set<NodeRelationType> MODEL_VISIBLE_RELATION_TYPES = Set.of(
            NodeRelationType.RELATED_TO,
            NodeRelationType.DEPENDS_ON,
            NodeRelationType.DERIVED_FROM,
            NodeRelationType.CONFLICTS_WITH,
            NodeRelationType.SUPPORTS);

    private final RouteRepository routeRepository;
    private final NodeRepository nodeRepository;
    private final NodeRelationRepository nodeRelationRepository;
    private final AgentInputProjectionRepository projectionRepository;
    private final MutableSourceFingerprinter fingerprinter;

    public StaleContextChecker(RouteRepository routeRepository,
                               NodeRepository nodeRepository,
                               NodeRelationRepository nodeRelationRepository,
                               AgentInputProjectionRepository projectionRepository,
                               MutableSourceFingerprinter fingerprinter) {
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.nodeRelationRepository = nodeRelationRepository;
        this.projectionRepository = projectionRepository;
        this.fingerprinter = fingerprinter;
    }

    /**
     * 校验一个变更型提案是否仍满足其依据的那份冻结模型输入的活体前置条件。
     */
    public void check(ActionProposal proposal, ActionExecutionContext context,
                      ContextSnapshot currentSnapshot) {
        if (isReadOnlyFamily(proposal)) {
            return;
        }
        if (!currentSnapshot.id().equals(proposal.baseContextSnapshotId())) {
            throw new StaleProposalException(
                    "Proposal baseContextSnapshotId does not match current snapshot");
        }
        if (!currentSnapshot.contextHash().equals(proposal.baseContextHash())) {
            throw new StaleProposalException(
                    "Proposal baseContextHash is stale: graph state has changed since the snapshot");
        }
        if (proposal.anchorRefs() != null && !proposal.anchorRefs().isEmpty()
                && context.routeId() != null) {
            Route route = routeRepository.findById(context.routeId()).orElse(null);
            if (route != null && route.tipNodeId() != null) {
                for (String anchorRef : proposal.anchorRefs()) {
                    if (anchorRef.startsWith("node:")) {
                        UUID anchorNodeId = UUID.fromString(anchorRef.substring(5));
                        if (!anchorNodeId.equals(route.tipNodeId())) {
                            throw new StaleProposalException(
                                    "Proposal anchor node is no longer the route tip");
                        }
                    }
                }
            }
        }
        verifyMutableSourcesStillFresh(currentSnapshot);
        verifyRelevantRelationsStillFresh(currentSnapshot);
    }

    private boolean isReadOnlyFamily(ActionProposal proposal) {
        try {
            ActionFamily family = ActionFamily.fromCode(proposal.actionFamily());
            return family == ActionFamily.RESPOND_TO_USER || family == ActionFamily.WAIT;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 核验全部模型可见的易变节点。当前投影行使用持久化的丰富 P1 指纹。
     * 指纹列为空的遗留投影行,只从其不可变冻结 payload 推导期望哈希;
     * 绝不用当前活状态重建历史。
     *
     * 节点缺失、跨项目或已撤回,都视为 stale——即使其持久化的正文字节
     * 本可以哈希出相同的值。
     */
    public void verifyMutableSourcesStillFresh(ContextSnapshot snapshot) {
        AgentInputProjectionRepository.FrozenInputProjection frozen =
                projectionRepository.findBySnapshotId(snapshot.id())
                        .orElseThrow(() -> new StaleProposalException(
                                "Proposal base context is stale: frozen input projection is unavailable"));

        Set<UUID> requiredIds = new HashSet<>(snapshot.includedNodeIds());
        requiredIds.addAll(snapshot.relatedNodeIds());

        Map<UUID, String> expectedById = new HashMap<>();
        Map<UUID, String> expectedType = new HashMap<>();
        boolean legacyDerived = frozen.sourceFingerprints().isEmpty();

        if (!legacyDerived) {
            for (MutableSourceFingerprint fingerprint : frozen.sourceFingerprints()) {
                expectedById.put(fingerprint.sourceId(), fingerprint.contentHash());
                expectedType.put(fingerprint.sourceId(), fingerprint.sourceType());
            }
        } else {
            deriveLegacyExpectedFingerprints(frozen, expectedById, expectedType);
        }

        if (!expectedById.keySet().containsAll(requiredIds)) {
            throw new StaleProposalException(
                    "Proposal base context is stale: frozen mutable-source preconditions are incomplete");
        }

        for (UUID id : requiredIds) {
            Node live = nodeRepository.findById(id)
                    .orElseThrow(() -> new StaleProposalException(
                            "Proposal base context is stale: model-visible node " + id + " no longer exists"));
            if (!snapshot.projectId().equals(live.projectId())) {
                throw new StaleProposalException(
                        "Proposal base context is stale: model-visible node " + id + " changed project ownership");
            }
            if (live.isRetracted()) {
                throw new StaleProposalException(
                        "Proposal base context is stale: model-visible node " + id + " was retracted");
            }
            String liveHash = legacyDerived
                    ? fingerprinter.modelVisibleNodeHash(live)
                    : fingerprinter.nodeBodyHash(live);
            if (!Objects.equals(expectedById.get(id), liveHash)) {
                throw new StaleProposalException(
                        "Proposal base context is stale: mutable source "
                                + expectedType.getOrDefault(id, "NODE") + " " + id
                                + " has changed since the model input was frozen");
            }
        }
    }

    private void deriveLegacyExpectedFingerprints(
            AgentInputProjectionRepository.FrozenInputProjection frozen,
            Map<UUID, String> expectedById,
            Map<UUID, String> expectedType) {
        AgentInputSnapshot projection;
        try {
            projection = AgentContracts.read(frozen.payload(), AgentInputSnapshot.class);
        } catch (RuntimeException ex) {
            throw new StaleProposalException(
                    "Proposal base context is stale: legacy frozen input preconditions cannot be derived");
        }
        projection.lineage().forEach(entry -> {
            if (entry.node() != null) {
                expectedById.put(entry.node().id(), fingerprinter.modelVisibleNodeHash(entry.node()));
                expectedType.put(entry.node().id(), "NODE");
            }
        });
        projection.relatedNodes().forEach(related -> {
            if (related.node() != null) {
                expectedById.put(related.node().id(), fingerprinter.modelVisibleNodeHash(related.node()));
                expectedType.put(related.node().id(), "RELATED_NODE");
            }
        });
    }

    /**
     * NODE_QUERY 只暴露触达其锚点的一跳有界 ACTIVE 语义关系。
     * 这里从活状态重新计算同一份有界集合,并逐项比较 source/target/type
     * 事实。这样既能发现关系被撤回/删除或新增了相关关系的情况,
     * 又不会让项目中无关的关系变更把提案误判为 stale。
     */
    private void verifyRelevantRelationsStillFresh(ContextSnapshot snapshot) {
        if (snapshot.operationType() != ContextOperationType.NODE_QUERY || snapshot.tipNodeId() == null) {
            return;
        }
        Set<RelationKey> frozenRelations = snapshot.relations().stream()
                .map(RelationKey::from)
                .collect(Collectors.toSet());
        Set<RelationKey> liveRelations = nodeRelationRepository
                .findActiveTouchingNode(snapshot.projectId(), snapshot.tipNodeId()).stream()
                .filter(relation -> MODEL_VISIBLE_RELATION_TYPES.contains(relation.relationType()))
                .map(RelationKey::from)
                .collect(Collectors.toSet());
        if (!frozenRelations.equals(liveRelations)) {
            throw new StaleProposalException(
                    "Proposal base context is stale: model-visible semantic relation set changed");
        }
    }

    private record RelationKey(UUID sourceNodeId, UUID targetNodeId, String relationType) {
        static RelationKey from(ContextRelation relation) {
            return new RelationKey(relation.sourceNodeId(), relation.targetNodeId(), relation.relationType());
        }

        static RelationKey from(NodeRelation relation) {
            return new RelationKey(relation.sourceNodeId(), relation.targetNodeId(), relation.relationType().code());
        }
    }

    /**
     * 为替换型提交(replacement)校验确定性的活体前置条件,时机是
     * 模型返回之后、任何拓扑变更之前。
     */
    public void verifyLiveExecutionPreconditions(UUID expectedSourceRouteId,
                                                 UUID expectedSourceRouteTip,
                                                 UUID targetNodeId) {
        Route route = routeRepository.findById(expectedSourceRouteId)
                .orElseThrow(() -> new StaleProposalException(
                        "Source route no longer exists: " + expectedSourceRouteId));
        if (route.tipNodeId() == null) {
            throw new StaleProposalException(
                    "Source route lost its tip before the replacement commit");
        }
        if (!Objects.equals(route.tipNodeId(), expectedSourceRouteTip)) {
            throw new StaleProposalException(
                    "Source route changed after the replacement snapshot: expected tip "
                            + expectedSourceRouteTip + ", current tip " + route.tipNodeId());
        }
        Set<UUID> seen = new HashSet<>();
        UUID current = route.tipNodeId();
        boolean containsTarget = false;
        while (current != null && seen.add(current)) {
            if (current.equals(targetNodeId)) {
                containsTarget = true;
                break;
            }
            Node node = nodeRepository.findById(current).orElse(null);
            current = node != null ? node.parentNodeId() : null;
        }
        if (!containsTarget) {
            throw new StaleProposalException(
                    "Replacement target is no longer on the source route lineage: " + targetNodeId);
        }
    }
}
