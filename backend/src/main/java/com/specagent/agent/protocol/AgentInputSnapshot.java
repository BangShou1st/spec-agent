package com.specagent.agent.protocol;

import java.util.List;
import java.util.UUID;
import com.specagent.retrieval.RetrievedContextItem;

/**
 * 文件名:AgentInputSnapshot.java
 *
 * 用途:单个决策周期面向模型的确定性输入投影,由 Runtime 构建并冻结
 * ({@code AgentInputSnapshot}),是 Brain 推理的唯一事实来源。
 *
 * 约束:所有身份标识都归 Runtime 所有。该投影由 Java 侧从持久化的
 * {@code ContextSnapshot} manifest 构建,Brain 绝不允许通过访问数据库
 * 自行重建这份状态。冻结的线上(wire)形状见 {@code contracts/README.md}。
 */
public record AgentInputSnapshot(String snapshotId,
                                 String contextHash,
                                 UUID projectId,
                                 UUID routeId,
                                 UUID anchorNodeId,
                                 RouteContextView routeContext,
                                 List<LineageEntry> lineage,
                                 List<ClaimView> effectiveClaims,
                                 SnapshotMetadata metadata,
                                 List<String> allowedSourceRefs,
                                 List<CapabilityDescriptor> availableCapabilities,
                                 SkillCatalogView availableSkills,
                                 List<CapabilityResultView> capabilityResults,
                                 List<RelationView> relations,
                                 List<RelatedNodeRef> relatedNodes,
                                 List<RetrievedContextItem> retrievedContext,
                                 AutonomyInputs autonomy) {

    public AgentInputSnapshot {
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
        effectiveClaims = effectiveClaims == null ? List.of() : List.copyOf(effectiveClaims);
        allowedSourceRefs = allowedSourceRefs == null ? List.of() : List.copyOf(allowedSourceRefs);
        availableCapabilities = availableCapabilities == null
                ? List.of() : List.copyOf(availableCapabilities);
        availableSkills = availableSkills == null
                ? SkillCatalogView.empty() : availableSkills;
        capabilityResults = capabilityResults == null
                ? List.of() : List.copyOf(capabilityResults);
        relations = relations == null ? List.of() : List.copyOf(relations);
        relatedNodes = relatedNodes == null ? List.of() : List.copyOf(relatedNodes);
        retrievedContext = retrievedContext == null ? List.of() : List.copyOf(retrievedContext);
    }

    /** 兼容旧调用的构造器:适用于 Skill 目录字段出现之前的调用方。 */
    public AgentInputSnapshot(String snapshotId,
                              String contextHash,
                              UUID projectId,
                              UUID routeId,
                              UUID anchorNodeId,
                              RouteContextView routeContext,
                              List<LineageEntry> lineage,
                              List<ClaimView> effectiveClaims,
                              SnapshotMetadata metadata,
                              List<String> allowedSourceRefs,
                              List<CapabilityDescriptor> availableCapabilities,
                              List<CapabilityResultView> capabilityResults,
                              List<RelationView> relations,
                              List<RelatedNodeRef> relatedNodes,
                              AutonomyInputs autonomy) {
        this(snapshotId, contextHash, projectId, routeId, anchorNodeId,
                routeContext, lineage, effectiveClaims, metadata, allowedSourceRefs,
                availableCapabilities, SkillCatalogView.empty(), capabilityResults,
                relations, relatedNodes, List.of(), autonomy);
    }

    /** 兼容构造器:适用于已经包含 Skills 的调用方。 */
    public AgentInputSnapshot(String snapshotId,
                              String contextHash,
                              UUID projectId,
                              UUID routeId,
                              UUID anchorNodeId,
                              RouteContextView routeContext,
                              List<LineageEntry> lineage,
                              List<ClaimView> effectiveClaims,
                              SnapshotMetadata metadata,
                              List<String> allowedSourceRefs,
                              List<CapabilityDescriptor> availableCapabilities,
                              SkillCatalogView availableSkills,
                              List<CapabilityResultView> capabilityResults,
                              List<RelationView> relations,
                              List<RelatedNodeRef> relatedNodes,
                              AutonomyInputs autonomy) {
        this(snapshotId, contextHash, projectId, routeId, anchorNodeId, routeContext,
                lineage, effectiveClaims, metadata, allowedSourceRefs,
                availableCapabilities, availableSkills, capabilityResults,
                relations, relatedNodes, List.of(), autonomy);
    }
}
