package com.specagent.retrieval;

import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.KnowledgeStatus;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeAuthorKind;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.RetrievalScope;
import com.specagent.retrieval.embedding.EmbeddingEnrichmentService;
import com.specagent.retrieval.embedding.EmbeddingEnrichmentWorker;
import com.specagent.retrieval.context.RetrievalSearchService;
import com.specagent.retrieval.embedding.FakeEmbeddingGateway;
import com.specagent.retrieval.index.RetrievalIndexRebuilder;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import com.specagent.retrieval.search.HybridRetriever;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Blocking integration gates for the hardened Memory + RAG vertical slice. */
@SpringBootTest(properties = "spec.agent.retrieval.embedding.provider=fake")
@ActiveProfiles("test")
@Transactional
class RetrievalV1HardeningIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired
    private RetrievalEntryRepository entryRepository;
    @Autowired
    private EmbeddingEnrichmentService enrichmentService;
    @Autowired
    private FakeEmbeddingGateway fakeEmbeddingGateway;
    @Autowired
    private HybridRetriever hybridRetriever;
    @Autowired
    private RetrievalSearchService retrievalSearchService;
    @Autowired
    private RetrievalIndexRebuilder indexRebuilder;

    @Test
    void ordinaryDecisionSnapshotExposesWorkspaceMemorySearch() {
        Project project = projectService.createProject("memory search 可见性");
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "当前任务是什么？", null, List.of(), true);

        ContextSnapshot context = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        var snapshot = snapshotBuilder.build(context);

        assertThat(snapshot.availableCapabilities())
                .anyMatch(capability -> capability.id().equals("memory.search"));
        assertThat(snapshot.allowedSourceRefs()).contains("route:" + routeId);
        assertThat(snapshot.lineage()).singleElement()
                .satisfies(entry -> assertThat(entry.node().id()).isEqualTo(node.id()));
    }

    @Test
    void forkRefreshesSharedAncestorRouteProvenanceWithoutRebuild() {
        Project project = projectService.createProject("fork route provenance refresh");
        UUID routeA = project.activeRouteId();
        Node n1 = nodeService.createRootNode(project.id(), routeA,
                "共享前缀一", null, List.of(), true);
        Node n2 = nodeService.createChildNode(project.id(), routeA, n1.id(),
                "共享前缀二", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeA, n2.id(), null, "已确认", "user");
        Node n3 = nodeService.createChildNode(project.id(), routeA, n2.id(),
                "仅原路线后续", null, List.of(), true);
        enrichmentService.enrichPending(project.id());

        String n1Ref = "node:" + n1.id();
        String n2Ref = "node:" + n2.id();
        String n3Ref = "node:" + n3.id();
        var before = entryRepository.findBySourceRef(project.id(), n1Ref).orElseThrow();
        assertThat(before.metadata().get("originRouteIds")).isEqualTo(List.of(routeA.toString()));
        assertThat(before.embeddingStatus()).isEqualTo("READY");

        Route fork = routeService.forkFromNode(project.id(), routeA, n2.id(), "共享前缀分支");

        var sharedN1 = entryRepository.findBySourceRef(project.id(), n1Ref).orElseThrow();
        var sharedN2 = entryRepository.findBySourceRef(project.id(), n2Ref).orElseThrow();
        var sourceOnlyN3 = entryRepository.findBySourceRef(project.id(), n3Ref).orElseThrow();
        assertThat(originRouteIds(sharedN1.metadata()))
                .containsExactlyInAnyOrder(routeA.toString(), fork.id().toString());
        assertThat(originRouteIds(sharedN2.metadata()))
                .containsExactlyInAnyOrder(routeA.toString(), fork.id().toString());
        assertThat(sharedN1.metadata().get("workspaceScoped")).isEqualTo(false);
        assertThat(sharedN1.routeId()).isNull();
        assertThat(sourceOnlyN3.metadata().get("originRouteIds"))
                .isEqualTo(List.of(routeA.toString()));
        assertThat(sharedN1.embeddingStatus()).isEqualTo("READY");
        assertThat(sharedN1.embeddingModel()).isEqualTo(FakeEmbeddingGateway.MODEL);
        assertThat(sharedN1.contentHash()).isEqualTo(before.contentHash());

        var projectSearch = retrievalSearchService.search(
                project.id(), fork.id(), "共享前缀二", RetrievalScope.PROJECT, 8);
        assertThat(projectSearch.items())
                .filteredOn(item -> item.sourceRef().equals(n2Ref))
                .singleElement()
                .satisfies(item -> assertThat(originRouteIds(item.provenance()))
                        .as("PROJECT search must expose refreshed shared provenance")
                        .contains(fork.id().toString()));
    }

    @Test
    void startRouteFromFloatingNodeRefreshesWorkspaceProvenance() {
        Project project = projectService.createProject("floating route provenance refresh");
        Node floating = nodeService.createFloatingWorkspaceNode(project.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "独立知识"), NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
        var before = entryRepository.findBySourceRef(project.id(), "node:" + floating.id()).orElseThrow();
        assertThat(before.metadata().get("originRouteIds")).isEqualTo(List.of());
        assertThat(before.metadata().get("workspaceScoped")).isEqualTo(true);

        var route = routeService.startRouteFromNode(project.id(), floating.id(), "从知识开始");

        var after = entryRepository.findBySourceRef(project.id(), "node:" + floating.id()).orElseThrow();
        assertThat(after.metadata().get("originRouteIds")).isEqualTo(List.of(route.id().toString()));
        assertThat(after.metadata().get("workspaceScoped")).isEqualTo(false);
        assertThat(after.routeId()).isEqualTo(route.id());
    }

    @Test
    void reanswerRefreshesInheritedPrefixButNotReplacedTarget() {
        Project project = projectService.createProject("reanswer provenance refresh");
        UUID routeA = project.activeRouteId();
        Node n1 = nodeService.createRootNode(project.id(), routeA,
                "前缀问题", null, List.of(), true);
        Node target = nodeService.createChildNode(project.id(), routeA, n1.id(),
                "待重新回答问题", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeA, target.id(), null, "旧答案", "user");

        Route reanswer = routeService.reanswerFromNode(project.id(), routeA, target.id(), "重新回答");

        var prefix = entryRepository.findBySourceRef(project.id(), "node:" + n1.id()).orElseThrow();
        var oldTarget = entryRepository.findBySourceRef(project.id(), "node:" + target.id()).orElseThrow();
        assertThat(originRouteIds(prefix.metadata()))
                .containsExactlyInAnyOrder(routeA.toString(), reanswer.id().toString());
        assertThat(oldTarget.metadata().get("originRouteIds"))
                .isEqualTo(List.of(routeA.toString()));
    }

    @Test
    void regenerateRefreshesInheritedPrefixButNotReplacedTarget() {
        Project project = projectService.createProject("regenerate provenance refresh");
        UUID routeA = project.activeRouteId();
        Node n1 = nodeService.createRootNode(project.id(), routeA,
                "重生成前缀", null, List.of(), true);
        Node target = nodeService.createChildNode(project.id(), routeA, n1.id(),
                "待替换问题", null, List.of(), true);

        var result = routeService.commitReplacementFromNode(
                project.id(), routeA, target.id(), target.id(), "重生成", "替换后的问题",
                null, List.of(), true, false);

        var prefix = entryRepository.findBySourceRef(project.id(), "node:" + n1.id()).orElseThrow();
        var oldTarget = entryRepository.findBySourceRef(project.id(), "node:" + target.id()).orElseThrow();
        assertThat(originRouteIds(prefix.metadata()))
                .containsExactlyInAnyOrder(routeA.toString(), result.replacementRoute().id().toString());
        assertThat(oldTarget.metadata().get("originRouteIds"))
                .isEqualTo(List.of(routeA.toString()));
    }

    @Test
    void longCanonicalRouteUsesBoundedWorkingLineageAndRecallsOldMemory() {
        Project project = projectService.createProject("long route retrieval");
        UUID routeId = project.activeRouteId();
        Node first = nodeService.createRootNode(project.id(), routeId,
                "早期项目启动记录。", null, List.of(), true);
        Node tip = first;
        Node oldMemory = null;
        for (int index = 2; index <= 150; index++) {
            tip = nodeService.createChildNode(project.id(), routeId, tip.id(),
                    index == 5 ? "会议时间限制：最多45分钟。" : "后续讨论节点 " + index,
                    null, List.of(), true);
            if (index == 5) {
                oldMemory = tip;
            }
        }
        Node historicalMeetingFact = oldMemory;

        ContextSnapshot context = contextBuilder.buildForNodeQuery(
                project.id(), routeId, tip.id(), "还有哪些会议时间方面的限制？");
        var projected = snapshotBuilder.build(context);

        assertThat(context.includedNodeIds()).hasSize(150);
        assertThat(projected.lineage()).hasSizeLessThanOrEqualTo(12);
        assertThat(projected.lineage()).noneMatch(entry -> entry.node().id().equals(first.id()));
        assertThat(projected.allowedSourceRefs()).doesNotContain("node:" + first.id());
        assertThat(projected.retrievedContext()).anySatisfy(item -> {
            assertThat(item.content()).contains("45分钟");
            assertThat(item.sourceRef()).isEqualTo("node:" + historicalMeetingFact.id());
            assertThat(item.scope()).isIn(RetrievalScope.ROUTE, RetrievalScope.PROJECT);
        });
        assertThat(AgentContracts.write(projected)).hasSizeLessThan(80_000);
    }

    @Test
    void workingContextKeepsTipWhenManifestTailIsDerivedMaterial() {
        Project project = projectService.createProject("derived material cannot evict tip");
        UUID routeId = project.activeRouteId();
        Node tip = nodeService.createRootNode(project.id(), routeId,
                "根问题", null, List.of(), true);
        for (int index = 1; index < 20; index++) {
            tip = nodeService.createChildNode(project.id(), routeId, tip.id(),
                    "路线问题 " + index, null, List.of(), true);
        }
        UUID tipId = tip.id();
        for (int index = 0; index < 24; index++) {
            nodeService.createWorkspaceNode(project.id(), routeId, tipId, NodeKind.KNOWLEDGE,
                    "NOTE", Map.of("text", "derived material " + index),
                    NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
        }

        ContextSnapshot context = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        var projected = snapshotBuilder.build(context);

        assertThat(context.includedNodeIds()).hasSize(44);
        assertThat(projected.lineage()).hasSize(12);
        assertThat(projected.lineage()).anyMatch(entry -> entry.node().id().equals(tipId));
        assertThat(projected.lineage()).noneMatch(entry ->
                entry.node().body().text() != null && entry.node().body().text().startsWith("derived material"));
    }

    @Test
    void workingContextCountsLongAnswerAndPatchWireContent() {
        Project project = projectService.createProject("working budget includes answers");
        UUID routeId = project.activeRouteId();
        Node tip = nodeService.createRootNode(project.id(), routeId,
                "历史问题 0", null, List.of(), true);
        for (int index = 0; index < 12; index++) {
            if (index > 0) {
                tip = nodeService.createChildNode(project.id(), routeId, tip.id(),
                        "历史问题 " + index, null, List.of(), true);
            }
            var answer = answerService.finalizeAnswer(project.id(), routeId, tip.id(), null,
                    "A".repeat(4_100), "user");
            if (index == 11) {
                answerPatchService.save(project.id(), routeId, tip.id(), answer.id(),
                        List.of(Claim.of(ClaimKind.GOAL, "P".repeat(3_000),
                                ClaimStatus.CONFIRMED, tip.id(), answer.id())), null);
            }
        }
        Node currentTip = nodeService.createChildNode(project.id(), routeId, tip.id(),
                "当前短问题", null, List.of(), true);
        ContextSnapshot context = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        var projected = snapshotBuilder.build(context);
        int lineageChars = AgentContracts.write(projected.lineage()).length();

        assertThat(projected.lineage()).anyMatch(entry -> entry.node().id().equals(currentTip.id()));
        assertThat(projected.lineage()).hasSizeLessThanOrEqualTo(12);
        assertThat(lineageChars).isLessThanOrEqualTo(12_000);
        assertThat(projected.lineage()).anyMatch(entry -> entry.patches().stream()
                .flatMap(patch -> patch.claims().stream())
                .anyMatch(claim -> claim.text().length() >= 3_000));
    }

    @Test
    void frozenSnapshotDoesNotReRetrieveAfterNewProjectEvidenceAppears() {
        Project project = projectService.createProject("frozen retrieval");
        UUID routeId = project.activeRouteId();
        Node original = nodeService.createRootNode(project.id(), routeId,
                "原始问题", null, List.of(), true);
        ContextSnapshot context = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        var first = snapshotBuilder.build(context);

        nodeService.createChildNode(project.id(), routeId, original.id(),
                "R4 新增且更相关的证据", null, List.of(), true);

        assertThat(snapshotBuilder.build(context)).isEqualTo(first);
        ContextSnapshot freshContext = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        assertThat(snapshotBuilder.build(freshContext).lineage())
                .anyMatch(entry -> entry.node().body().text().contains("R4"));
    }

    @Test
    void routeAndProjectRetrievalKeepTheirScopeMeaning() {
        Project project = projectService.createProject("scope retrieval");
        UUID routeA = project.activeRouteId();
        Node a = nodeService.createRootNode(project.id(), routeA,
                "内部系统不收费。", null, List.of(), true);
        var routeB = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "外部客户");
        Node b = nodeService.createRootNode(project.id(), routeB.id(),
                "外部客户按量收费。", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeB.id(), b.id(), null,
                "外部客户按量收费。", "user");

        Set<String> routeRefs = Set.of("node:" + a.id());
        var routeOnly = hybridRetriever.retrieve(new RetrievalQuery(
                project.id(), routeA, a.id(), "EXPLICIT_SEARCH", "收费模式",
                List.of(RetrievalScope.ROUTE), 8, 8_000, routeRefs, Set.of(a.id())));
        assertThat(routeOnly).noneMatch(candidate -> candidate.entry().routeId() != null
                && candidate.entry().routeId().equals(routeB.id()));

        var projectWide = hybridRetriever.retrieve(new RetrievalQuery(
                project.id(), routeA, a.id(), "EXPLICIT_SEARCH", "按量收费",
                List.of(RetrievalScope.PROJECT), 8, 8_000, Set.of(), Set.of()));
        assertThat(projectWide).anySatisfy(candidate -> {
            assertThat(candidate.entry().routeId()).isEqualTo(routeB.id());
            assertThat(candidate.entry().scope()).isEqualTo(RetrievalScope.ROUTE);
        });
    }

    @Test
    void vectorEntriesMoveFromPendingToReadyAndRejectOtherEmbeddingSpaces() {
        Project project = projectService.createProject("vector lane");
        Node node = nodeService.createFloatingWorkspaceNode(project.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "向量检索的确定性测试内容"),
                NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
        String sourceRef = "node:" + node.id();

        var pending = entryRepository.findBySourceRef(project.id(), sourceRef).orElseThrow();
        assertThat(pending.embeddingStatus()).isEqualTo("PENDING");
        assertThat(enrichmentService.enrichPending(project.id())).isGreaterThanOrEqualTo(1);

        var ready = entryRepository.findBySourceRef(project.id(), sourceRef).orElseThrow();
        assertThat(ready.embeddingStatus()).isEqualTo("READY");
        assertThat(ready.embeddingModel()).isEqualTo(FakeEmbeddingGateway.MODEL);
        var vectorMatches = entryRepository.vector(project.id(),
                fakeEmbeddingGateway.embed("向量检索的确定性测试内容").orElseThrow(), 8, List.of());
        assertThat(vectorMatches).isPresent();
        assertThat(vectorMatches.orElseThrow()).isNotEmpty();
        var wrongSpaceMatches = entryRepository.vector(project.id(),
                new com.specagent.retrieval.EmbeddingGateway.Embedding(
                        "other-model", FakeEmbeddingGateway.DIMENSIONS,
                        new float[FakeEmbeddingGateway.DIMENSIONS]), 8, List.of());
        assertThat(wrongSpaceMatches).isPresent();
        assertThat(wrongSpaceMatches.orElseThrow()).isEmpty();

        nodeService.reviseUserDraft(project.id(), node.id(), "NOTE",
                Map.of("text", "内容发生变化，必须重新生成向量"));
        var stale = entryRepository.findBySourceRef(project.id(), sourceRef).orElseThrow();
        assertThat(stale.embeddingStatus()).isEqualTo("PENDING");
        assertThat(stale.embeddingModel()).isNull();
    }

    @Test
    void enrichmentWorkerMovesPendingRowsWithoutSnapshotSideEffects() {
        Project project = projectService.createProject("vector worker trigger");
        Node node = nodeService.createFloatingWorkspaceNode(project.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "worker tick enrichment"), NodeAuthorKind.USER,
                KnowledgeStatus.PROPOSED);
        String sourceRef = "node:" + node.id();
        assertThat(entryRepository.findBySourceRef(project.id(), sourceRef).orElseThrow()
                .embeddingStatus()).isEqualTo("PENDING");

        new EmbeddingEnrichmentWorker(enrichmentService, entryRepository).tick();

        assertThat(entryRepository.findBySourceRef(project.id(), sourceRef).orElseThrow()
                .embeddingStatus()).isEqualTo("READY");
    }

    @Test
    void nodeOnlyProjectRetrievalDeclaresOriginRouteProvenance() {
        Project project = projectService.createProject("node provenance");
        UUID routeA = project.activeRouteId();
        Node routeAAnchor = nodeService.createRootNode(project.id(), routeA,
                "内部工具", null, List.of(), true);
        var routeB = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "外部收费");
        Node routeBKnowledge = nodeService.createRootNode(project.id(), routeB.id(),
                "外部客户采用按量收费", null, List.of(), true);

        var result = retrievalSearchService.search(project.id(), routeA, "按量收费",
                RetrievalScope.PROJECT, 8);
        assertThat(result.items()).anySatisfy(item -> {
            assertThat(item.sourceRef()).isEqualTo("node:" + routeBKnowledge.id());
            assertThat(item.provenance().get("originRouteIds"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .contains(routeB.id().toString());
        });
        assertThat(result.items()).noneMatch(item -> item.sourceRef().equals("node:" + routeAAnchor.id())
                && item.content().contains("按量收费"));
    }

    @Test
    void resourceMiddleChunkAndProvenanceRemainRetrievable() {
        Project project = projectService.createProject("resource chunks");
        UUID routeId = project.activeRouteId();
        Node anchor = nodeService.createRootNode(project.id(), routeId,
                "查询外部资料", null, List.of(), true);
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 60; index++) {
            text.append("资料段落 ").append(index).append("：");
            if (index == 31) {
                text.append("系统要求数据留存180天，随后按策略安全删除。");
            }
            text.append("一般说明 ").append("资料内容 ".repeat(120)).append("\n\n");
        }
        Node resource = nodeService.createWorkspaceNode(project.id(), routeId, anchor.id(),
                NodeKind.RESOURCE, "TEXT", Map.of("text", text.toString()),
                NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);

        var entries = entryRepository.findByProject(project.id()).stream()
                .filter(entry -> entry.sourceRef().startsWith("resource-chunk:" + resource.id()))
                .toList();
        assertThat(entries).hasSizeGreaterThan(50);

        var result = hybridRetriever.retrieve(new RetrievalQuery(
                project.id(), routeId, anchor.id(), "RESOURCE", "数据留存要求",
                List.of(RetrievalScope.RESOURCE), 8, 8_000, Set.of(), Set.of()));
        assertThat(result).anySatisfy(candidate -> {
            assertThat(candidate.entry().content()).contains("180天");
            assertThat(candidate.entry().metadata()).containsKeys("resourceId", "chunk", "lineRange");
            assertThat(candidate.entry().metadata().get("originRouteIds").toString())
                    .contains(routeId.toString());
        });
    }

    @Test
    void secretShapedNodeAnswerClaimAndResourceTextNeverEntersIndex() {
        Project project = projectService.createProject("secret exclusion");
        UUID routeId = project.activeRouteId();
        String secret = "RAG_TEST_SECRET_123456789";
        Node question = nodeService.createRootNode(project.id(), routeId,
                "请输入凭据", null, List.of(), true);
        var answer = answerService.finalizeAnswer(project.id(), routeId, question.id(), null,
                "api_key=" + secret, "user");
        answerPatchService.save(project.id(), routeId, question.id(), answer.id(),
                List.of(Claim.of(ClaimKind.GOAL, "credential:=" + secret,
                        ClaimStatus.CONFIRMED, question.id(), answer.id())), null);
        nodeService.createFloatingWorkspaceNode(project.id(), NodeKind.KNOWLEDGE, "NOTE",
                Map.of("text", "password: " + secret), NodeAuthorKind.USER,
                KnowledgeStatus.PROPOSED);
        nodeService.createFloatingWorkspaceNode(project.id(), NodeKind.RESOURCE, "TEXT",
                Map.of("text", "authorization: bearer " + secret), NodeAuthorKind.USER,
                KnowledgeStatus.PROPOSED);

        assertThat(entryRepository.findByProject(project.id()))
                .allSatisfy(entry -> {
                    assertThat(entry.content()).doesNotContain(secret);
                    assertThat(entry.metadata().toString()).doesNotContain(secret);
                });
    }

    @Test
    void administrativeRebuildRestoresDerivedIndexWithoutChangingCanonicalNode() {
        Project project = projectService.createProject("rebuild");
        Node node = nodeService.createFloatingWorkspaceNode(project.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "可恢复的 canonical 内容"), NodeAuthorKind.USER,
                KnowledgeStatus.PROPOSED);
        int canonicalCount = nodeService.listProject(project.id()).size();
        entryRepository.deleteProject(project.id());
        assertThat(entryRepository.findByProject(project.id())).isEmpty();

        indexRebuilder.rebuildProject(project.id());

        assertThat(nodeService.getNode(node.id())).isPresent();
        assertThat(nodeService.listProject(project.id())).hasSize(canonicalCount);
        assertThat(entryRepository.findBySourceRef(project.id(), "node:" + node.id())).isPresent();
    }

    @Test
    void repeatedFreshSnapshotsDoNotEraseReadyEmbedding() {
        Project project = projectService.createProject("snapshot incremental index");
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "稳定的历史事实", null, List.of(), true);
        enrichmentService.enrichPending(project.id());
        var sourceRef = "node:" + node.id();
        assertThat(entryRepository.findBySourceRef(project.id(), sourceRef).orElseThrow()
                .embeddingStatus()).isEqualTo("READY");

        for (int index = 0; index < 3; index++) {
            ContextSnapshot context = contextBuilder.buildFromActiveRoute(
                    project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
            snapshotBuilder.build(context);
        }

        var after = entryRepository.findBySourceRef(project.id(), sourceRef).orElseThrow();
        assertThat(after.embeddingStatus()).isEqualTo("READY");
        assertThat(after.embeddingModel()).isEqualTo(FakeEmbeddingGateway.MODEL);
    }

    private List<String> originRouteIds(Map<String, Object> metadata) {
        Object raw = metadata.get("originRouteIds");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }
}
