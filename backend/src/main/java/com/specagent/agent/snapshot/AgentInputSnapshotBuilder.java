package com.specagent.agent.snapshot;

import com.specagent.agent.protocol.AgentContractException;
import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AnswerView;
import com.specagent.agent.protocol.AutonomyInputs;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AvailableSkillView;
import com.specagent.agent.protocol.SkillCatalogView;
import com.specagent.agent.protocol.CapabilityDescriptor;
import com.specagent.agent.protocol.CapabilityResultView;
import com.specagent.agent.protocol.ClaimView;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.protocol.LineageEntry;
import com.specagent.agent.protocol.NodeBodyView;
import com.specagent.agent.protocol.NodeView;
import com.specagent.agent.protocol.OptionView;
import com.specagent.agent.protocol.RelatedNodeRef;
import com.specagent.agent.protocol.RelationView;
import com.specagent.agent.protocol.PatchView;
import com.specagent.agent.protocol.RouteContextView;
import com.specagent.agent.protocol.SnapshotMetadata;
import com.specagent.agent.protocol.UserRequiredSkillView;
import com.specagent.retrieval.RetrievedContextItem;
import com.specagent.retrieval.context.RetrievalContextService;
import com.specagent.workspace.context.ContextRelation;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.capability.CapabilityInvocationRecord;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.capability.CapabilityVisibilityService;
import com.specagent.skill.discovery.SkillCatalogEntry;
import com.specagent.skill.discovery.SkillDiscoveryContext;
import com.specagent.skill.discovery.SkillDiscoveryService;
import com.specagent.skill.discovery.SkillHostToolVisibility;
import com.specagent.skill.runtime.SkillSearchHostTool;
import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.context.RequirementState;
import com.specagent.workspace.context.RequirementStateBuilder;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Service;


import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:AgentInputSnapshotBuilder.java
 *
 * 用途:把冻结的 {@link ContextSnapshot} 确定性地投影成带版本的
 * {@code AgentInputSnapshot} wire 契约,即模型实际收到的输入快照。
 *
 * 持久化的 {@code ContextSnapshot} 清单保持不动、始终是血缘(lineage)
 * 的权威来源;本构建器只把清单所列的记录按清单自身的顺序投影成通用
 * Graph 语言。Python 绝不通过直接访问数据库来重建这份状态。
 *
 * <strong>冻结输入完整性:</strong>ContextSnapshot 的第一次投影会作为
 * 不可变的 {@link com.specagent.agent.snapshot.FrozenInputProjection} 行
 * 持久化一次(payload 哈希 + 契约版本,insert-if-absent)。同一 snapshot
 * 之后的每次投影都原样回放存储的 payload,因此重试/续跑/修复绝不可能
 * 基于活的易变记录(可编辑的节点正文、相关节点正文、路由标签、能力结果)
 * 悄悄重建模型输入。冻结行一旦损坏、被篡改或身份不符,一律 fail-closed
 * 抛出类型化异常——绝不回退到活数据重建。
 *
 * {@code projectTitle} 仅作为低权威的展示元数据传递,任何快照消费者
 * 都不得把它提升为 objective。
 */
@Service
public class AgentInputSnapshotBuilder {

    /** 有界的观察数量上限:提示词永远不会收到无上限的目录。 */
    private static final int RECENT_CAPABILITY_RESULTS_LIMIT = 5;

    /**
     * 为可见性过滤设置的更宽取数窗口:同层路由里可能保存着最新的记录,
     * 因此投影会扫描更宽的近期窗口并保留其中可见的最新条目。
     * 投影后的数量仍受上面那个上限约束。
     */
    private static final int VISIBILITY_FETCH_LIMIT = 20;

    /**
     * 规范化冻结 payload 的硬上限。投影本身在构造上就是有界的
     * (血缘有界、关系只取 1 跳、资源摘要有界、能力观察有界);
     * 一旦超出此上限,按类型化 fail-closed 失败处理,绝不静默截断。
     */
    private static final int MAX_FROZEN_PAYLOAD_CHARS = 1_000_000;

    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteRepository routeRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final RequirementStateBuilder requirementStateBuilder;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityVisibilityService capabilityVisibilityService;
    private final SkillDiscoveryService skillDiscoveryService;
    private final SkillHostToolVisibility skillHostToolVisibility;
    private final CapabilityInvocationRepository capabilityInvocationRepository;
    private final RunAttributionLookupPort runAttributionLookup;
    private final AgentInputProjectionRepository projectionRepository;
    private final MutableSourceFingerprinter fingerprinter;
    private final Json json;
    private final RetrievalContextService retrievalContextService;
    private final WorkingContextSelector workingContextSelector;

    public AgentInputSnapshotBuilder(NodeRepository nodeRepository,
                                     AnswerRepository answerRepository,
                                     AnswerPatchRepository answerPatchRepository,
                                     RouteRepository routeRepository,
                                     RouteHistoryResolver routeHistoryResolver,
                                     RequirementStateBuilder requirementStateBuilder,
                                     CapabilityRegistry capabilityRegistry,
                                     CapabilityVisibilityService capabilityVisibilityService,
                                     SkillDiscoveryService skillDiscoveryService,
                                     SkillHostToolVisibility skillHostToolVisibility,
                                     CapabilityInvocationRepository capabilityInvocationRepository,
                                     RunAttributionLookupPort runAttributionLookup,
                                     AgentInputProjectionRepository projectionRepository,
                                     MutableSourceFingerprinter fingerprinter,
                                     Json json,
                                     RetrievalContextService retrievalContextService,
                                     WorkingContextSelector workingContextSelector) {
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.routeRepository = routeRepository;
        this.routeHistoryResolver = routeHistoryResolver;
        this.requirementStateBuilder = requirementStateBuilder;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityVisibilityService = capabilityVisibilityService;
        this.skillDiscoveryService = skillDiscoveryService;
        this.skillHostToolVisibility = skillHostToolVisibility;
        this.capabilityInvocationRepository = capabilityInvocationRepository;
        this.runAttributionLookup = runAttributionLookup;
        this.projectionRepository = projectionRepository;
        this.fingerprinter = fingerprinter;
        this.json = json;
        this.retrievalContextService = retrievalContextService;
        this.workingContextSelector = workingContextSelector;
    }

    /**
     * 为一次冻结 snapshot 和一次 run 构建完整的请求信封。
     */
    public AgentRequestEnvelope buildEnvelope(UUID runId,
                                                ContextSnapshot snapshot,
                                                AgentEvent event,
                                                DecisionBudget budget) {
        return new AgentRequestEnvelope(
                AgentProtocol.INPUT_PROTOCOL_VERSION,
                runId,
                event,
                build(snapshot, event),
                List.of(),
                budget);
    }

    /**
     * 把一个冻结 snapshot 投影成面向模型的输入快照。
     *
     * 加载或冻结(load-or-freeze):第一次投影从权威记录构建,
     * 把规范化 payload 持久冻结并返回;同一 snapshot 之后的每次投影都
     * 逐字回放存储的 payload。并发的首次冻结通过 snapshot 唯一索引仲裁
     * (先写者胜);落败方返回胜者的冻结 payload。
     */
    public AgentInputSnapshot build(ContextSnapshot snapshot) {
        return build(snapshot, null);
    }

    private AgentInputSnapshot build(ContextSnapshot snapshot, AgentEvent event) {
        Set<UUID> mandatoryNodeIds = new HashSet<>();
        if (snapshot.tipNodeId() != null) {
            mandatoryNodeIds.add(snapshot.tipNodeId());
        }
        if (event != null && event.anchorNodeId() != null) {
            mandatoryNodeIds.add(event.anchorNodeId());
        }
        return projectionRepository.findBySnapshotId(snapshot.id())
                .<AgentInputSnapshot>map(frozen -> loadFrozen(snapshot, frozen))
                .orElseGet(() -> {
                    LiveProjection projection = buildFromLiveRecords(snapshot, mandatoryNodeIds);
                    return freezeOrAdopt(snapshot, projection);
                });
    }

    /**
     * 校验并回放一份持久化的冻结投影。每项检查失败都 fail-closed 抛出
     * 类型化损坏异常——绝不回退到活数据重建——因为冻结 payload 就是
     * "模型看到了什么"的审计与可复现性证据。
     */
    private AgentInputSnapshot loadFrozen(ContextSnapshot snapshot,
                                          AgentInputProjectionRepository.FrozenInputProjection frozen) {
        boolean versionOk = AgentInputProjectionRepository.SUPPORTED_PROJECTION_VERSION.equals(frozen.projectionVersion())
                || AgentInputProjectionRepository.LEGACY_PROJECTION_VERSION_V1.equals(frozen.projectionVersion())
                || "agent-input.v2".equals(frozen.projectionVersion());
        if (!versionOk) {
            throw new FrozenProjectionCorruptedException(
                    "Frozen input projection carries unsupported version '"
                            + frozen.projectionVersion() + "' for snapshot " + snapshot.id());
        }
        if (!Hashes.sha256Hex(frozen.payload()).equals(frozen.payloadHash())) {
            throw new FrozenProjectionCorruptedException(
                    "Frozen input projection payload hash mismatch for snapshot "
                            + snapshot.id() + " — payload was tampered with or corrupted");
        }
        AgentInputSnapshot projection;
        try {
            projection = AgentContracts.read(frozen.payload(), AgentInputSnapshot.class);
        } catch (AgentContractException ex) {
            throw new FrozenProjectionCorruptedException(
                    "Frozen input projection payload does not parse for snapshot "
                            + snapshot.id() + ": " + ex.getMessage());
        }
        if (!snapshot.id().toString().equals(projection.snapshotId())
                || !snapshot.contextHash().equals(projection.contextHash())) {
            throw new FrozenProjectionCorruptedException(
                    "Frozen input projection identity mismatch for snapshot "
                            + snapshot.id() + " — payload belongs to another snapshot context");
        }
        return projection;
    }

    /**
     * 持久化 snapshot 的首次冻结。当并发竞争中对手已获胜(insert-if-absent
     * 落败)时,丢弃本次构建结果,改为加载并返回胜者的冻结 payload——
     * 并发首次冻结绝不分叉,也绝不"后写者胜"。
     */
    private AgentInputSnapshot freezeOrAdopt(ContextSnapshot snapshot,
                                             LiveProjection liveProjection) {
        AgentInputSnapshot projection = liveProjection.snapshot();
        String payload = AgentContracts.write(projection);
        if (payload.length() > MAX_FROZEN_PAYLOAD_CHARS) {
            throw new FrozenProjectionSizeExceededException(
                    "Frozen input projection exceeds the "
                            + MAX_FROZEN_PAYLOAD_CHARS + " char bound for snapshot "
                            + snapshot.id() + ": " + payload.length());
        }
        List<MutableSourceFingerprint> fingerprints = fingerprinter.fingerprintsFor(
                liveProjection.workingNodes(), liveProjection.relatedNodes());
        boolean won = projectionRepository.insertIfAbsent(
                new AgentInputProjectionRepository.FrozenInputProjection(
                        UUID.randomUUID(), snapshot.id(),
                        AgentInputProjectionRepository.SUPPORTED_PROJECTION_VERSION,
                        payload, Hashes.sha256Hex(payload), fingerprints, Instant.now()));
        if (!won) {
            AgentInputProjectionRepository.FrozenInputProjection winner =
                    projectionRepository.findBySnapshotId(snapshot.id())
                            .orElseThrow(() -> new FrozenProjectionCorruptedException(
                                    "Lost the freeze race but no frozen projection row exists for snapshot "
                                            + snapshot.id()));
            return loadFrozen(snapshot, winner);
        }
        return projection;
    }

    /**
     * 从活的权威存储中投影"恰好是清单所列"的记录。对同一 snapshot 身份
     * 至多调用一次——即首次冻结——之后绝不再调用。
     */
    private LiveProjection buildFromLiveRecords(ContextSnapshot snapshot,
                                                Set<UUID> mandatoryNodeIds) {
        List<Node> manifestNodes = snapshot.includedNodeIds().stream()
                .map(nodeRepository::findById)
                .map(optional -> optional.orElseThrow(() -> new IllegalStateException(
                        "Context snapshot included missing node")))
                .toList();
        List<Answer> answers = loadAnswers(snapshot);
        Map<UUID, Answer> answersByNodeId = answers.stream()
                .collect(java.util.stream.Collectors.toMap(Answer::nodeId, answer -> answer,
                        (left, right) -> right, LinkedHashMap::new));
        Map<UUID, List<AnswerPatch>> patchesByAnswerId = groupPatchesByAnswer(snapshot);
        List<Node> workingLineageNodes = selectWorkingNodes(snapshot, manifestNodes,
                mandatoryNodeIds, answersByNodeId, patchesByAnswerId);
        List<Node> allContextNodes = new ArrayList<>(manifestNodes);
        for (Node node : workingLineageNodes) {
            if (allContextNodes.stream().noneMatch(existing -> existing.id().equals(node.id()))) {
                allContextNodes.add(node);
            }
        }
        List<Node> relatedNodes = loadRelatedNodes(snapshot, allContextNodes);
        List<RelatedNodeRef> relatedRefs = relatedNodeRefs(snapshot, relatedNodes);
        // 每次首次冻结构建只做一次 Skill 发现投影:同一份目录同时用于
        // wire 字段和 skill.search 可见性门禁,两者永远不会不一致
        // (未来更重的检索器也无法对同一个冻结 snapshot 产出两份不同目录)。
        SkillCatalogView skillCatalog =
                availableSkills(snapshot, workingLineageNodes, relatedNodes);
        List<ClaimView> effectiveClaims = effectiveClaims(snapshot);
        String retrievalQueryText = retrievalQueryText(snapshot, allContextNodes, effectiveClaims);
        Set<String> mandatoryRetrievalRefs = new HashSet<>();
        effectiveClaims.forEach(claim -> {
            if (claim.sourceAnswerId() != null) {
                mandatoryRetrievalRefs.add("answer:" + claim.sourceAnswerId());
            }
            if (claim.sourceNodeId() != null) {
                mandatoryRetrievalRefs.add("node:" + claim.sourceNodeId());
            }
        });
        List<RetrievedContextItem> retrievedContext;
        SnapshotMetadata projectionMetadata=metadata(snapshot);
        if(retrievalContextService.usesSharedPython()) {
            var retrieval=retrievalContextService.retrieveWithStatus(snapshot,mandatoryRetrievalRefs,retrievalQueryText);
            retrievedContext=retrieval.items(); var state=retrieval.state();
            projectionMetadata=new SnapshotMetadata(projectionMetadata.projectTitle(),new SnapshotMetadata.RetrievalState(
                    (String)state.get("retrievalEngineVersion"),(String)state.get("profileId"),(String)state.get("indexGeneration"),
                    (Boolean)state.get("vectorUnavailable"),Boolean.TRUE.equals(state.get("supplementalRetrievalUnavailable"))));
        } else retrievedContext=retrievalContextService.retrieve(snapshot,mandatoryRetrievalRefs,retrievalQueryText);
        AgentInputSnapshot projection = new AgentInputSnapshot(
                snapshot.id().toString(),
                snapshot.contextHash(),
                snapshot.projectId(),
                snapshot.routeId(),
                snapshot.tipNodeId(),
                routeContext(snapshot),
                lineage(workingLineageNodes, answersByNodeId, patchesByAnswerId),
                effectiveClaims,
                projectionMetadata,
                allowedSourceRefs(snapshot, workingLineageNodes, effectiveClaims,
                        relatedRefs, retrievedContext),
                visibleCapabilityDescriptors(snapshot, workingLineageNodes, relatedNodes, skillCatalog),
                skillCatalog,
                capabilityResults(snapshot),
                relations(snapshot),
                relatedRefs,
                retrievedContext,
                new AutonomyInputs("ADVISOR"));
        return new LiveProjection(projection, workingLineageNodes, relatedNodes);
    }

    /**
     * 在应用工作记忆边界之前,先把路由的权威父级血缘与清单里的派生材料
     * 分开。路由归属来自 RouteHistoryResolver,绝不来自检索投影对父链的
     * 猜测。
     */
    private List<Node> selectWorkingNodes(ContextSnapshot snapshot,
                                          List<Node> manifestNodes,
                                          Set<UUID> mandatoryNodeIds,
                                          Map<UUID, Answer> answersByNodeId,
                                          Map<UUID, List<AnswerPatch>> patchesByAnswerId) {
        Map<UUID, Node> allById = new LinkedHashMap<>();
        manifestNodes.forEach(node -> allById.put(node.id(), node));
        if (snapshot.tipNodeId() != null) {
            nodeRepository.findById(snapshot.tipNodeId()).ifPresent(node -> allById.put(node.id(), node));
        }
        if (mandatoryNodeIds != null) {
            for (UUID mandatoryId : mandatoryNodeIds) {
                if (mandatoryId == null) {
                    continue;
                }
                Node node = nodeRepository.findById(mandatoryId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Working context mandatory node is missing: " + mandatoryId));
                if (!snapshot.projectId().equals(node.projectId())) {
                    throw new IllegalStateException(
                            "Working context mandatory node belongs to another project: " + mandatoryId);
                }
                allById.put(node.id(), node);
            }
        }

        List<Node> canonicalRouteLineage;
        if (snapshot.tipNodeId() == null) {
            canonicalRouteLineage = manifestNodes;
        } else {
            canonicalRouteLineage = routeHistoryResolver.resolveLineage(snapshot.tipNodeId()).stream()
                    .map(id -> allById.computeIfAbsent(id, key -> nodeRepository.findById(key)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Working context lineage node is missing: " + key))))
                    .toList();
        }
        Set<UUID> canonicalIds = canonicalRouteLineage.stream()
                .map(Node::id).collect(java.util.stream.Collectors.toSet());
        List<Node> derivedMaterial = allById.values().stream()
                .filter(node -> !canonicalIds.contains(node.id()))
                .toList();
        return workingContextSelector.select(
                canonicalRouteLineage,
                derivedMaterial,
                mandatoryNodeIds,
                nodes -> modelFacingLineageChars(nodes, answersByNodeId, patchesByAnswerId));
    }

    private int modelFacingLineageChars(List<Node> nodes,
                                        Map<UUID, Answer> answersByNodeId,
                                        Map<UUID, List<AnswerPatch>> patchesByAnswerId) {
        return AgentContracts.write(lineage(nodes, answersByNodeId, patchesByAnswerId)).length();
    }

    @SuppressWarnings("unchecked")
    private String retrievalQueryText(ContextSnapshot snapshot,
                                      List<Node> lineageNodes,
                                      List<ClaimView> claims) {
        List<String> parts = new ArrayList<>();
        List<String> explicitUserQueries = new ArrayList<>();
        if (snapshot.specialInputs() != null && !snapshot.specialInputs().isBlank()) {
            Map<String, Object> inputs = json.read(snapshot.specialInputs(), Map.class);
            if (inputs != null) {
                for (String key : List.of("userQuestion", "userInstruction", "oldQuestion", "oldPurpose")) {
                    Object value = inputs.get(key);
                    if (value instanceof String text && !text.isBlank()) {
                        parts.add(text);
                        if (snapshot.operationType() == ContextOperationType.NODE_QUERY) {
                            explicitUserQueries.add(text);
                        }
                    }
                }
            }
        }
        // NODE_QUERY 的显式提问就是检索意图。若把当前 tip 正文追加到该文本后,
        // pg_trgm 词相似度可能被稀释到足以掩盖一条更早的匹配事实;
        // 而 tip 本身已经在必选工作上下文中,不会被遗漏。
        if (snapshot.operationType() == ContextOperationType.NODE_QUERY
                && !explicitUserQueries.isEmpty()) {
            return String.join("\n", explicitUserQueries);
        }
        if (snapshot.tipNodeId() != null) {
            lineageNodes.stream()
                    .filter(node -> snapshot.tipNodeId().equals(node.id()))
                    .findFirst()
                    .ifPresent(node -> {
                        if (node.question() != null && !node.question().isBlank()) {
                            parts.add(node.question());
                        } else if (node.contentText() != null && !node.contentText().isBlank()) {
                            parts.add(node.contentText());
                        }
                    });
        }
        claims.stream().limit(24).map(ClaimView::text)
                .filter(text -> text != null && !text.isBlank()).forEach(parts::add);
        return String.join("\n", parts);
    }

    /**
     * 有界的 1 跳语义上下文投影到 wire 上,方向与持久化 snapshot 中存储的
     * 完全一致。
     */
    private List<RelationView> relations(ContextSnapshot snapshot) {
        return snapshot.relations().stream()
                .map(r -> new RelationView(r.sourceNodeId(), r.targetNodeId(), r.relationType()))
                .toList();
    }

    /**
     * 加载 1 跳语义上下文另一端的活 {@link Node} 领域对象。每个
     * {@code snapshot.relatedNodeId()} 都要加载并校验:节点必须仍然存在、
     * 属于 snapshot 的项目、且未被撤回。校验失败会让投影大声失败
     * (冻结 snapshot 列出了一个已不属于其项目活图的节点),
     * 绝不静默丢弃上下文。已在血缘中的相关节点会被跳过——它已经完整
     * 出现在血缘里,再以"相关节点"身份重复一次不会增加任何上下文。
     * 相关节点永不进入血缘,也绝不污染血缘。
     */
    private List<Node> loadRelatedNodes(ContextSnapshot snapshot, List<Node> lineageNodes) {
        Set<UUID> lineageIds = lineageNodes.stream().map(Node::id)
                .collect(java.util.stream.Collectors.toSet());
        List<Node> related = new ArrayList<>();
        for (UUID relatedId : snapshot.relatedNodeIds()) {
            if (lineageIds.contains(relatedId)) {
                continue;
            }
            Node node = nodeRepository.findById(relatedId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Context snapshot listed missing related node: " + relatedId));
            verifyRelatedNode(snapshot, node);
            related.add(node);
        }
        return List.copyOf(related);
    }

    private void verifyRelatedNode(ContextSnapshot snapshot, Node node) {
        if (!node.projectId().equals(snapshot.projectId())) {
            throw new IllegalStateException(
                    "Context snapshot listed related node from another project: " + node.id());
        }
        if (node.isRetracted()) {
            throw new IllegalStateException(
                    "Context snapshot listed retracted related node: " + node.id());
        }
    }

    /**
     * 1 跳语义上下文的相关规范节点列表,每项都带显式来源信息(关系类型 +
     * 相对锚点的方向)以及相关节点自身投影出的 NodeView/正文——模型读到
     * 的是真实正文内容,绝不只是一堆不透明的 id。
     */
    private List<RelatedNodeRef> relatedNodeRefs(ContextSnapshot snapshot, List<Node> relatedNodes) {
        UUID anchor = snapshot.tipNodeId();
        List<RelatedNodeRef> refs = new ArrayList<>();
        for (Node related : relatedNodes) {
            // 来源信息取自存储的关系中触达该相关节点的那一条;snapshot
            // 保证每个列出的相关 id 至少存在一条这样的关系。
            ContextRelation relation = snapshot.relations().stream()
                    .filter(r -> r.sourceNodeId().equals(related.id())
                            || r.targetNodeId().equals(related.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Related node has no relation in snapshot context: " + related.id()));
            boolean outgoing = anchor != null && anchor.equals(relation.sourceNodeId());
            String direction = outgoing ? "OUTGOING" : "INCOMING";
            refs.add(new RelatedNodeRef(related.id(), relation.relationType(), direction,
                    nodeView(related)));
        }
        return List.copyOf(refs);
    }

    /**
     * 经过权限、可用性与相关性过滤的能力描述符,投影到 wire 契约上。
     * 过滤(权限、provider 可用性、与上下文节点类型的 {@code supports}
     * 兼容性、目录上限)由 {@link CapabilityVisibilityService} 负责;
     * 本构建器只把有界的运行时描述符映射到带版本的 wire 形状——包括
     * 有界的输入 schema 和驱动可见性的 {@code supports} 事实——使模型
     * 能为动态 provider 构造合法调用,却永远看不到实现类、连接、
     * 端点或凭据。
     */
    private List<CapabilityDescriptor> visibleCapabilityDescriptors(ContextSnapshot snapshot,
                                                                    List<Node> lineageNodes,
                                                                    List<Node> relatedNodes,
                                                                    SkillCatalogView skillCatalog) {
        List<String> contextKinds = new ArrayList<>();
        for (Node node : lineageNodes) {
            contextKinds.add(node.kind().code());
            if (node.subtype() != null && !node.subtype().isBlank()) {
                contextKinds.add(node.kind().code() + ":" + node.subtype());
            }
        }
        for (Node node : relatedNodes) {
            contextKinds.add(node.kind().code());
            if (node.subtype() != null && !node.subtype().isBlank()) {
                contextKinds.add(node.kind().code() + ":" + node.subtype());
            }
        }
        CapabilityQueryContext context = new CapabilityQueryContext(
                Set.of(), List.copyOf(contextKinds), Map.of());
        boolean skillsPresent = skillHostToolVisibility.anyEnabledSkill(snapshot.projectId());
        // skill.search 只是截断兜底:仅当本次构建的 Skill 目录投影被截断时
        // 才对外可见。直接传入预计算的目录,不再做第二次发现调用。
        boolean catalogTruncated = skillCatalog.truncated();
        return capabilityVisibilityService.visibleCapabilities(context).stream()
                .filter(descriptor -> skillsPresent
                        || !isSkillHostTool(descriptor.capabilityId()))
                .filter(descriptor -> catalogTruncated
                        || !SkillSearchHostTool.CAPABILITY_ID.equals(descriptor.capabilityId()))
                .map(descriptor -> new CapabilityDescriptor(
                        descriptor.capabilityId(),
                        descriptor.version(),
                        descriptor.description(),
                        descriptor.inputSchema(),
                        descriptor.readOnly(),
                        descriptor.sideEffectClass().code(),
                        descriptor.supports()))
                .toList();
    }

    /**
     * Skill Host 函数工具只在项目确实存在已启用的 Skill 可激活/可读取时
     * 才暴露——"已安装 != 已加载"对过程性知识工具和 Skill 本体同等适用。
     * 判定依据是确定性的结构化事实(已注册能力 id 的前缀),绝不是用户
     * 的措辞。
     */
    private boolean isSkillHostTool(String capabilityId) {
        return capabilityId.startsWith("skill.");
    }

    /**
     * 面向一次全新 Decision 上下文的有界 Skill 目录。发现在这里执行——
     * 即首次冻结时——因此每个全新的续跑 snapshot 都会得到一份新发现的
     * 目录,而同一个冻结 snapshot 永远回放完全相同的冻结投影。跨边界
     * 传递的只有身份 + 有界元数据;完整的 SKILL.md 留在激活之后。
     */
    private SkillCatalogView availableSkills(ContextSnapshot snapshot,
                                            List<Node> lineageNodes,
                                            List<Node> relatedNodes) {
        List<String> resourceKinds = new ArrayList<>();
        for (Node node : lineageNodes) {
            resourceKinds.add(node.kind().code());
        }
        for (Node node : relatedNodes) {
            resourceKinds.add(node.kind().code());
        }
        List<String> recentCapabilityIds = capabilityInvocationRepository
                .findRecentCompleted(snapshot.projectId(), RECENT_CAPABILITY_RESULTS_LIMIT)
                .stream().map(CapabilityInvocationRecord::capabilityId).toList();
        SkillDiscoveryContext context = new SkillDiscoveryContext(
                snapshot.operationType() == null ? null : snapshot.operationType().name(),
                List.copyOf(resourceKinds), recentCapabilityIds, Map.of());
        var projection = skillDiscoveryService.discover(context);
        List<AvailableSkillView> skills = projection.entries().stream()
                .map(this::toSkillView)
                .toList();
        return new SkillCatalogView(skills, projection.truncated(),
                projection.fingerprint(), userRequiredSkill(lineageNodes, relatedNodes, skills));
    }

    /**
     * 用户在本上下文中显式指定的 Skill 指令,没有则为 null。通过 "/"
     * Skill 选择器编辑的节点会把所选 skill 持久化在 {@code skillId}
     * content 键下;只有当该 id 出现在已发现(已启用)的目录中,指令才会
     * 进入 wire snapshot——被禁用或已移除的 Skill 会被静默降级为"不存在",
     * 而不是以一条无法执行的指令到达模型。血缘节点优先于相关节点;
     * 多个绑定节点之间按确定性的血缘顺序取第一个。
     */
    private UserRequiredSkillView userRequiredSkill(List<Node> lineageNodes,
                                                    List<Node> relatedNodes,
                                                    List<AvailableSkillView> skills) {
        for (Node node : lineageNodes) {
            UserRequiredSkillView directive = boundDirective(node, skills);
            if (directive != null) {
                return directive;
            }
        }
        for (Node node : relatedNodes) {
            UserRequiredSkillView directive = boundDirective(node, skills);
            if (directive != null) {
                return directive;
            }
        }
        return null;
    }

    private UserRequiredSkillView boundDirective(Node node, List<AvailableSkillView> skills) {
        Object raw = node.content().get("skillId");
        if (!(raw instanceof String skillId) || skillId.isBlank()) {
            return null;
        }
        return skills.stream()
                .filter(skill -> skill.skillId().equalsIgnoreCase(skillId.strip()))
                .findFirst()
                .map(skill -> new UserRequiredSkillView(skill.skillId(), skill.name()))
                .orElse(null);
    }

    private AvailableSkillView toSkillView(SkillCatalogEntry entry) {
        return new AvailableSkillView(entry.skillId(), entry.name(),
                entry.description(), entry.compatibilityHint());
    }

    /**
     * 近期已完成的能力调用,作为有界的观察条目。它们只是后续循环的
     * 证据,绝不是自动确认为真的事实;数量保持很小,提示词永远不会
     * 收到无上限的目录。
     */
    private List<CapabilityResultView> capabilityResults(ContextSnapshot snapshot) {
        List<CapabilityInvocationRecord> recent = capabilityInvocationRepository
                .findRecentCompleted(snapshot.projectId(), VISIBILITY_FETCH_LIMIT);
        Set<UUID> lineage = new java.util.HashSet<>(snapshot.includedNodeIds());
        Map<UUID, CapabilityObservationVisibility.RunAttribution> runsById = new HashMap<>();
        for (CapabilityInvocationRecord record : recent) {
            if (record.runId() != null && !runsById.containsKey(record.runId())) {
                runsById.put(record.runId(), loadRunAttribution(record.runId()));
            }
        }
        return recent.stream()
                .filter(record -> CapabilityObservationVisibility.isVisible(
                        snapshot.routeId(), lineage,
                        runsById.get(record.runId()),
                        CapabilityObservationVisibility.referencedNodeIds(record)))
                .limit(RECENT_CAPABILITY_RESULTS_LIMIT)
                .map(this::capabilityResultView)
                .toList();
    }

    private CapabilityObservationVisibility.RunAttribution loadRunAttribution(UUID runId) {
        return runAttributionLookup.attributionOf(runId).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private CapabilityResultView capabilityResultView(CapabilityInvocationRecord record) {
        Map<String, Object> stored = record.result() == null ? Map.of() : record.result();
        Object content = stored.get("content");
        Object provenance = stored.get("provenance");
        Object refs = stored.get("sourceRefs");
        return new CapabilityResultView(
                record.id().toString(),
                record.capabilityId(),
                record.status().name(),
                content instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(),
                refs instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList() : List.of(),
                provenance instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
    }

    private RouteContextView routeContext(ContextSnapshot snapshot) {
        if (snapshot.routeId() == null) {
            // 无路由的 NODE_QUERY 上下文(游离节点):读取上下文就是锚点节点
            // 本身。直接构建显式的无路由视图,不用 null 路由 id 去查库。
            return new RouteContextView(null, snapshot.tipNodeId(), null);
        }
        String label = routeRepository.findById(snapshot.routeId())
                .map(Route::label)
                .orElse(null);
        return new RouteContextView(snapshot.routeId(), snapshot.tipNodeId(), label);
    }

    private List<LineageEntry> lineage(List<Node> lineageNodes,
                                       Map<UUID, Answer> answersByNodeId,
                                       Map<UUID, List<AnswerPatch>> patchesByAnswerId) {
        List<LineageEntry> lineage = new ArrayList<>();
        for (Node node : lineageNodes) {
            Answer answer = answersByNodeId.get(node.id());
            List<PatchView> patches = answer == null
                    ? List.of()
                    : patchesByAnswerId.getOrDefault(answer.id(), List.of()).stream()
                            .map(this::patchView)
                            .toList();
            lineage.add(new LineageEntry(nodeView(node),
                    answer == null ? null : answerView(answer), patches));
        }
        return lineage;
    }

    private List<ClaimView> effectiveClaims(ContextSnapshot snapshot) {
        RequirementState state = requirementStateBuilder.buildForContext(snapshot);
        return state.claims().stream().map(this::claimView).toList();
    }

    @SuppressWarnings("unchecked")
    private SnapshotMetadata metadata(ContextSnapshot snapshot) {
        String raw = snapshot.specialInputs();
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return new SnapshotMetadata(null);
        }
        Map<String, Object> specialInputs = json.read(raw, Map.class);
        Object title = specialInputs == null ? null : specialInputs.get("projectTitle");
        return new SnapshotMetadata(title instanceof String text && !text.isBlank() ? text : null);
    }

    private List<String> allowedSourceRefs(ContextSnapshot snapshot,
                                           List<Node> workingLineageNodes,
                                           List<ClaimView> effectiveClaims,
                                           List<RelatedNodeRef> relatedRefs,
                                           List<RetrievedContextItem> retrievedContext) {
        List<String> refs = new ArrayList<>();
        Set<UUID> workingNodeIds = workingLineageNodes.stream()
                .map(Node::id).collect(java.util.stream.Collectors.toSet());
        Map<UUID, Answer> answersByNodeId = new HashMap<>();
        for (Answer answer : loadAnswers(snapshot)) {
            if (workingNodeIds.contains(answer.nodeId())) {
                refs.add("answer:" + answer.id());
                answersByNodeId.put(answer.nodeId(), answer);
            }
        }
        Map<UUID, List<AnswerPatch>> patchesByAnswerId = groupPatchesByAnswer(snapshot);
        workingLineageNodes.forEach(node -> refs.add("node:" + node.id()));
        answersByNodeId.values().stream()
                .flatMap(answer -> patchesByAnswerId.getOrDefault(answer.id(), List.of()).stream())
                .map(patch -> "patch:" + patch.id())
                .forEach(refs::add);
        effectiveClaims.stream().flatMap(claim -> java.util.stream.Stream.of(
                        claim.sourceNodeId() == null ? null : "node:" + claim.sourceNodeId(),
                        claim.sourceAnswerId() == null ? null : "answer:" + claim.sourceAnswerId()))
                .filter(java.util.Objects::nonNull)
                .forEach(refs::add);
        // 相关节点也是一等来源引用:模型可以在其正文上做 grounding,
        // 也可以在 CONNECT_NODE 提案中引用它们
        // (例如把锚点与一个直接可见的相关节点建立关系)。
        relatedRefs.stream().map(RelatedNodeRef::nodeId).distinct()
                .forEach(id -> refs.add("node:" + id));
        if (retrievedContext != null) {
            retrievedContext.stream().map(RetrievedContextItem::sourceRef).distinct()
                    .forEach(refs::add);
        }
        refs.add("context:" + snapshot.id());
        if (snapshot.routeId() != null) {
            refs.add("route:" + snapshot.routeId());
        }
        return refs;
    }

    private List<Answer> loadAnswers(ContextSnapshot snapshot) {
        List<Answer> answers = new ArrayList<>();
        for (UUID id : snapshot.includedAnswerIds()) {
            answers.add(answerRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException(
                            "Context snapshot included missing answer: " + id)));
        }
        return answers;
    }

    private Map<UUID, List<AnswerPatch>> groupPatchesByAnswer(ContextSnapshot snapshot) {
        Map<UUID, List<AnswerPatch>> byAnswer = new HashMap<>();
        for (UUID id : snapshot.includedPatchIds()) {
            AnswerPatch patch = answerPatchRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException(
                            "Context snapshot included missing patch: " + id));
            byAnswer.computeIfAbsent(patch.sourceAnswerId(), key -> new ArrayList<>()).add(patch);
        }
        return byAnswer;
    }

    /** 通用 Graph 语言的节点投影;绝不出现工作流专有名称。 */
    private NodeView nodeView(Node node) {
        List<OptionView> options = node.options().stream()
                .map(option -> new OptionView(option.id(), option.label()))
                .toList();
        // 交互节点保留提问文本;其他类型把主内容载荷以文本形式暴露,
        // 使节点正文保持同一种形状。
        String text = node.question() != null ? node.question() : node.contentText();
        return new NodeView(node.id(),
                new NodeBodyView(text, options, node.allowFreeAnswer()),
                node.kind().code());
    }

    private AnswerView answerView(Answer answer) {
        return new AnswerView(
                answer.id(),
                answer.nodeId(),
                parseOptionId(answer.selectedOptionId()),
                answer.freeText());
    }

    private UUID parseOptionId(String selectedOptionId) {
        if (selectedOptionId == null || selectedOptionId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(selectedOptionId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Persisted answer carries a malformed option id: " + selectedOptionId);
        }
    }

    private PatchView patchView(AnswerPatch patch) {
        return new PatchView(patch.id(),
                patch.claims().stream().map(this::claimView).toList());
    }

    private ClaimView claimView(Claim claim) {
        return new ClaimView(
                claim.kind().code(),
                claim.text(),
                claim.status().code(),
                claim.confidence(),
                claim.sourceNodeId(),
                claim.sourceAnswerId());
    }

    private record LiveProjection(AgentInputSnapshot snapshot,
                                  List<Node> workingNodes,
                                  List<Node> relatedNodes) {
        private LiveProjection {
            workingNodes = workingNodes == null ? List.of() : List.copyOf(workingNodes);
            relatedNodes = relatedNodes == null ? List.of() : List.copyOf(relatedNodes);
        }
    }
}
