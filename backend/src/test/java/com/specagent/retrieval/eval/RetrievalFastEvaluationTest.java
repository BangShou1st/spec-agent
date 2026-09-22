package com.specagent.retrieval.eval;

import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.agent.snapshot.WorkingContextSelector;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.KnowledgeStatus;
import com.specagent.node.Node;
import com.specagent.node.NodeAuthorKind;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.retrieval.api.RetrievalQuery;
import com.specagent.retrieval.api.RetrievalScope;
import com.specagent.retrieval.api.RetrievedContextItem;
import com.specagent.retrieval.context.RetrievalContextService;
import com.specagent.retrieval.embedding.EmbeddingEnrichmentService;
import com.specagent.retrieval.embedding.NoopEmbeddingGateway;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import com.specagent.retrieval.search.HybridRetriever;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic offline retrieval corpus for the blocking RAG gate. Every
 * metric below is calculated from the ordered retrieval results produced by
 * the real hybrid retriever or the real bounded retrieval-context selector.
 */
@SpringBootTest(properties = "spec.agent.retrieval.embedding.provider=fake")
@ActiveProfiles("test")
@Transactional
class RetrievalFastEvaluationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired
    private RetrievalContextService retrievalContextService;
    @Autowired
    private RetrievalEntryRepository entryRepository;
    @Autowired
    private EmbeddingEnrichmentService enrichmentService;
    @Autowired
    private HybridRetriever hybridRetriever;

    @Test
    void retrievalFastWritesMetricsFromDeterministicCorpus() throws Exception {
        long started = System.nanoTime();
        List<ScenarioResult> scenarios = new ArrayList<>();
        int maxWorkingLineage = 0;

        // E1 — long route recall, plus the working-memory evidence used by the
        // artifact. The historical fact is outside the recent route window.
        Project longRouteProject = projectService.createProject("eval E1 long route");
        UUID longRoute = longRouteProject.activeRouteId();
        Node oldFact = nodeService.createRootNode(longRouteProject.id(), longRoute,
                "会议时间限制：最多45分钟。", null, List.of(), true);
        Node longTip = oldFact;
        for (int index = 2; index <= 30; index++) {
            longTip = nodeService.createChildNode(longRouteProject.id(), longRoute,
                    longTip.id(), "评估路线节点 " + index, null, List.of(), true);
        }
        ContextSnapshot longContext = contextBuilder.buildForNodeQuery(
                longRouteProject.id(), longRoute, longTip.id(), "会议时间方面有哪些限制？");
        var longSnapshot = snapshotBuilder.build(longContext);
        maxWorkingLineage = Math.max(maxWorkingLineage, longSnapshot.lineage().size());
        scenarios.add(fromSnapshot("E1-long-route-recall", longRouteProject.id(), longRoute,
                List.of("node:" + oldFact.id()), longSnapshot.retrievedContext()));

        // E2/E3 — the same project has two routes with conflicting evidence.
        Project routeProject = projectService.createProject("eval E2 route isolation");
        UUID routeA = routeProject.activeRouteId();
        Node routeAFact = nodeService.createRootNode(routeProject.id(), routeA,
                "内部系统不收费。", null, List.of(), true);
        var routeB = routeService.createRoute(routeProject.id(), RouteLifecycleStatus.OPEN, "外部客户");
        Node routeBFact = nodeService.createRootNode(routeProject.id(), routeB.id(),
                "外部客户按量收费。", null, List.of(), true);
        scenarios.add(fromRouteEntries("E2-current-route-isolation", routeProject.id(), routeA,
                List.of("node:" + routeAFact.id()), retrieve(new RetrievalQuery(
                        routeProject.id(), routeA, routeAFact.id(), "ROUTE", "收费",
                        List.of(RetrievalScope.ROUTE), 8, 8_000,
                        Set.of("node:" + routeAFact.id()), Set.of(routeAFact.id())))));
        scenarios.add(fromEntries("E3-cross-route-project-retrieval", routeProject.id(), routeA,
                List.of("node:" + routeBFact.id()), retrieve(new RetrievalQuery(
                        routeProject.id(), routeA, routeAFact.id(), "PROJECT", "按量收费",
                        List.of(RetrievalScope.PROJECT), 8, 8_000, Set.of(), Set.of()))));

        // E4 — another project contains identical text; project scoping must
        // still make the P2 row impossible to return for a P1 query.
        Project p1 = projectService.createProject("eval E4 P1");
        Node p1Fact = nodeService.createFloatingWorkspaceNode(p1.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "跨项目隔离的相似事实"), NodeAuthorKind.USER,
                KnowledgeStatus.PROPOSED);
        Project p2 = projectService.createProject("eval E4 P2");
        Node p2Fact = nodeService.createFloatingWorkspaceNode(p2.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "跨项目隔离的相似事实"), NodeAuthorKind.USER,
                KnowledgeStatus.PROPOSED);
        scenarios.add(fromEntries("E4-wrong-project-isolation", p1.id(), null,
                List.of("node:" + p1Fact.id()), retrieve(new RetrievalQuery(
                        p1.id(), null, null, "PROJECT", "跨项目隔离的相似事实",
                        List.of(RetrievalScope.PROJECT), 8, 8_000, Set.of(), Set.of()))));
        assertThat(entryRepository.findBySourceRef(p2.id(), "node:" + p2Fact.id())).isPresent();

        // E5 — resource chunks retain their source reference and are retrieved
        // through the resource lane.
        Project resourceProject = projectService.createProject("eval E5 resource");
        UUID resourceRoute = resourceProject.activeRouteId();
        Node resourceAnchor = nodeService.createRootNode(resourceProject.id(), resourceRoute,
                "查询外部资料", null, List.of(), true);
        Node resource = nodeService.createWorkspaceNode(resourceProject.id(), resourceRoute,
                resourceAnchor.id(), NodeKind.RESOURCE, "TEXT",
                Map.of("text", "一般资料。".repeat(120) + "数据留存要求是180天。"),
                NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
        String resourceRef = entryRepository.findByProject(resourceProject.id()).stream()
                .filter(entry -> entry.sourceRef().startsWith("resource-chunk:" + resource.id()))
                .filter(entry -> entry.content().contains("180天"))
                .findFirst().orElseThrow().sourceRef();
        scenarios.add(fromEntries("E5-resource-chunk", resourceProject.id(), resourceRoute,
                List.of(resourceRef), retrieve(new RetrievalQuery(
                        resourceProject.id(), resourceRoute, resourceAnchor.id(), "RESOURCE",
                        "数据留存要求180天", List.of(RetrievalScope.RESOURCE), 8, 8_000,
                        Set.of(), Set.of()))));

        // E6 — authority ordering is observed from the real fused ordering.
        Project authorityProject = projectService.createProject("eval E6 authority");
        String authorityText = "权威排序的确定性事实";
        Node confirmed = nodeService.createFloatingWorkspaceNode(authorityProject.id(),
                NodeKind.KNOWLEDGE, "NOTE", Map.of("text", authorityText),
                NodeAuthorKind.USER, KnowledgeStatus.CONFIRMED);
        Node proposed = nodeService.createFloatingWorkspaceNode(authorityProject.id(),
                NodeKind.KNOWLEDGE, "NOTE", Map.of("text", authorityText),
                NodeAuthorKind.AGENT, KnowledgeStatus.PROPOSED);
        List<RetrievalEntry> authorityResults = retrieve(new RetrievalQuery(
                authorityProject.id(), null, null, "PROJECT", authorityText,
                List.of(RetrievalScope.PROJECT), 8, 8_000, Set.of(), Set.of()));
        assertThat(authorityResults).isNotEmpty();
        assertThat(authorityResults.get(0).sourceRef()).isEqualTo("node:" + confirmed.id());
        scenarios.add(fromEntries("E6-authority-ordering", authorityProject.id(), null,
                List.of("node:" + confirmed.id()), authorityResults));
        assertThat(entryRepository.findBySourceRef(authorityProject.id(), "node:" + proposed.id()))
                .isPresent();

        // E7 — the context selector removes duplicate content hashes while
        // preserving ordered source references.
        Project duplicateProject = projectService.createProject("eval E7 duplicate");
        UUID duplicateRoute = duplicateProject.activeRouteId();
        Node duplicateA = nodeService.createRootNode(duplicateProject.id(), duplicateRoute,
                "重复事实：每周一同步。", null, List.of(), true);
        Node duplicateB = nodeService.createChildNode(duplicateProject.id(), duplicateRoute,
                duplicateA.id(), "重复事实：每周一同步。", null, List.of(), true);
        ContextSnapshot duplicateContext = contextBuilder.buildForNodeQuery(
                duplicateProject.id(), duplicateRoute, duplicateB.id(), "重复事实 每周一同步");
        List<RetrievedContextItem> duplicateItems = retrievalContextService.retrieve(
                duplicateContext, Set.of(), "重复事实 每周一同步");
        scenarios.add(fromSnapshot("E7-duplicate-elimination", duplicateProject.id(), duplicateRoute,
                List.of("node:" + duplicateA.id(), "node:" + duplicateB.id()), duplicateItems));

        // E8 — after enrichment, vector is one of the actual candidate lanes.
        Project vectorProject = projectService.createProject("eval E8 vector");
        Node vectorNode = nodeService.createFloatingWorkspaceNode(vectorProject.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "向量 lane 的确定性检索事实"), NodeAuthorKind.USER,
                KnowledgeStatus.PROPOSED);
        enrichmentService.enrichPending(vectorProject.id());
        RetrievalQuery vectorQuery = new RetrievalQuery(
                vectorProject.id(), null, null, "PROJECT", "向量 lane 的确定性检索事实",
                List.of(RetrievalScope.PROJECT), 8, 8_000, Set.of(), Set.of());
        assertThat(hybridRetriever.retrieve(vectorQuery)).anySatisfy(candidate ->
                assertThat(candidate.lanes()).contains("vector"));
        scenarios.add(fromEntries("E8-vector-lane", vectorProject.id(), null,
                List.of("node:" + vectorNode.id()), retrieve(vectorQuery)));

        // E9 — provider unavailability only removes vector candidates; lexical
        // retrieval remains live after the derived row becomes UNAVAILABLE.
        Project fallbackProject = projectService.createProject("eval E9 fallback");
        Node fallbackNode = nodeService.createFloatingWorkspaceNode(fallbackProject.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "provider unavailable 时仍可用的 lexical 事实"),
                NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
        new EmbeddingEnrichmentService(new NoopEmbeddingGateway(), entryRepository)
                .enrichPending(fallbackProject.id());
        RetrievalQuery fallbackQuery = new RetrievalQuery(
                fallbackProject.id(), null, null, "PROJECT", "provider unavailable lexical 事实",
                List.of(RetrievalScope.PROJECT), 8, 8_000, Set.of(), Set.of());
        assertThat(hybridRetriever.retrieve(fallbackQuery)).noneMatch(candidate ->
                candidate.lanes().contains("vector"));
        scenarios.add(fromEntries("E9-vector-unavailable-fallback", fallbackProject.id(), null,
                List.of("node:" + fallbackNode.id()), retrieve(fallbackQuery)));

        // E10 — secret-shaped content is absent from the index.
        Project secretProject = projectService.createProject("eval E10 secret");
        Node safeNode = nodeService.createFloatingWorkspaceNode(secretProject.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "安全索引内容"), NodeAuthorKind.USER,
                KnowledgeStatus.PROPOSED);
        Node secretNode = nodeService.createFloatingWorkspaceNode(secretProject.id(), NodeKind.KNOWLEDGE,
                "NOTE", Map.of("text", "password: eval-secret-should-never-index"),
                NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
        assertThat(entryRepository.findBySourceRef(secretProject.id(), "node:" + secretNode.id()))
                .isEmpty();
        scenarios.add(fromEntries("E10-secret-exclusion", secretProject.id(), null,
                List.of("node:" + safeNode.id()), retrieve(new RetrievalQuery(
                        secretProject.id(), null, null, "PROJECT", "安全索引内容",
                        List.of(RetrievalScope.PROJECT), 8, 8_000, Set.of(), Set.of()))));

        assertThat(scenarios).hasSize(10);
        assertThat(scenarios).allSatisfy(scenario ->
                assertThat(scenario.expectedRelevantRefs()).isNotEmpty());

        long relevantRefCount = scenarios.stream()
                .mapToLong(scenario -> scenario.expectedRelevantRefs().size()).sum();
        long hitCount = scenarios.stream().mapToLong(ScenarioResult::hitCount).sum();
        double recallAtK = relevantRefCount == 0 ? 0d : (double) hitCount / relevantRefCount;
        double mrr = scenarios.stream().mapToDouble(ScenarioResult::reciprocalRank).average().orElse(0d);
        long selectedCount = scenarios.stream().mapToLong(s -> s.selectedEntries().size()).sum();
        long wrongProjectCount = scenarios.stream()
                .flatMap(scenario -> scenario.selectedEntries().stream()
                        .filter(entry -> !scenario.projectId().equals(entry.projectId())))
                .count();
        List<ScenarioResult> routeScoped = scenarios.stream()
                .filter(ScenarioResult::routeScoped).toList();
        long routeSelectedCount = routeScoped.stream()
                .mapToLong(scenario -> scenario.selectedEntries().size()).sum();
        long crossRouteCount = routeScoped.stream()
                .flatMap(scenario -> scenario.selectedEntries().stream()
                        .filter(entry -> entry.routeId() != null
                                && !scenario.routeId().equals(entry.routeId())))
                .count();
        long totalSelectedRefs = scenarios.stream()
                .flatMap(scenario -> scenario.selectedRefs().stream()).count();
        long distinctSelectedRefs = scenarios.stream()
                .flatMap(scenario -> scenario.selectedRefs().stream()).distinct().count();
        double duplicateRate = totalSelectedRefs == 0 ? 0d
                : 1d - ((double) distinctSelectedRefs / totalSelectedRefs);
        int selectedCharsMax = scenarios.stream()
                .mapToInt(ScenarioResult::selectedChars).max().orElse(0);
        boolean vectorReady = entryRepository.findByProject(vectorProject.id()).stream()
                .anyMatch(entry -> "READY".equals(entry.embeddingStatus()));
        boolean vectorFallbackSelected = scenarios.stream()
                .filter(scenario -> scenario.name().equals("E9-vector-unavailable-fallback"))
                .anyMatch(scenario -> !scenario.selectedEntries().isEmpty());

        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        String artifact = "{\n"
                + "  \"scenarioCount\": " + scenarios.size() + ",\n"
                + "  \"relevantRefCount\": " + relevantRefCount + ",\n"
                + "  \"Recall@K\": " + format(recallAtK) + ",\n"
                + "  \"MRR\": " + format(mrr) + ",\n"
                + "  \"wrongProjectContamination\": "
                + format(selectedCount == 0 ? 0d : (double) wrongProjectCount / selectedCount) + ",\n"
                + "  \"crossRouteContamination\": "
                + format(routeSelectedCount == 0 ? 0d : (double) crossRouteCount / routeSelectedCount) + ",\n"
                + "  \"duplicateRate\": " + format(duplicateRate) + ",\n"
                + "  \"selectedItemCount\": " + selectedCount + ",\n"
                + "  \"selectedCharsMax\": " + selectedCharsMax + ",\n"
                + "  \"workingLineageMax\": " + maxWorkingLineage + ",\n"
                + "  \"maxWorkingLineageEntries\": "
                + WorkingContextSelector.MAX_WORKING_LINEAGE_ENTRIES + ",\n"
                + "  \"maxWorkingLineageChars\": "
                + WorkingContextSelector.MAX_WORKING_LINEAGE_CHARS + ",\n"
                + "  \"vectorReady\": " + vectorReady + ",\n"
                + "  \"vectorUnavailableFallbackSelected\": " + vectorFallbackSelected + ",\n"
                + "  \"latencyMs\": " + latencyMs + "\n"
                + "}\n";
        Path output = Path.of("build", "eval-retrieval", "retrieval-fast.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, artifact);

        assertThat(hitCount).isGreaterThan(0);
        assertThat(vectorReady).isTrue();
        assertThat(vectorFallbackSelected).isTrue();
        assertThat(wrongProjectCount).isZero();
        assertThat(crossRouteCount).isZero();
    }

    private List<RetrievalEntry> retrieve(RetrievalQuery query) {
        List<RetrievalEntry> selected = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        int chars = 0;
        for (HybridRetriever.Candidate candidate : hybridRetriever.retrieve(query)) {
            RetrievalEntry entry = candidate.entry();
            if (!refs.add(entry.sourceRef())) {
                continue;
            }
            if (chars + entry.content().length() > query.maxChars()) {
                break;
            }
            selected.add(entry);
            chars += entry.content().length();
            if (selected.size() >= query.maxItems()) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private ScenarioResult fromEntries(String name, UUID projectId, UUID routeId,
                                       List<String> expected,
                                       List<RetrievalEntry> entries) {
        List<String> refs = entries.stream().map(RetrievalEntry::sourceRef).toList();
        int chars = entries.stream().mapToInt(entry -> entry.content().length()).sum();
        return new ScenarioResult(name, projectId, routeId, false, expected, refs, entries, chars);
    }

    private ScenarioResult fromRouteEntries(String name, UUID projectId, UUID routeId,
                                            List<String> expected,
                                            List<RetrievalEntry> entries) {
        List<String> refs = entries.stream().map(RetrievalEntry::sourceRef).toList();
        int chars = entries.stream().mapToInt(entry -> entry.content().length()).sum();
        return new ScenarioResult(name, projectId, routeId, true, expected, refs, entries, chars);
    }

    private ScenarioResult fromSnapshot(String name, UUID projectId, UUID routeId,
                                        List<String> expected,
                                        List<RetrievedContextItem> items) {
        Map<String, RetrievalEntry> entriesByRef = entryRepository.findByProject(projectId).stream()
                .collect(java.util.stream.Collectors.toMap(RetrievalEntry::sourceRef,
                        entry -> entry, (left, right) -> left, LinkedHashMap::new));
        List<RetrievalEntry> entries = items.stream().map(RetrievedContextItem::sourceRef)
                .map(entriesByRef::get).filter(java.util.Objects::nonNull).toList();
        List<String> refs = items.stream().map(RetrievedContextItem::sourceRef).toList();
        int chars = items.stream().mapToInt(item -> item.content().length()).sum();
        return new ScenarioResult(name, projectId, routeId, false, expected, refs, entries, chars);
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private record ScenarioResult(String name,
                                  UUID projectId,
                                  UUID routeId,
                                  boolean routeScoped,
                                  List<String> expectedRelevantRefs,
                                  List<String> selectedRefs,
                                  List<RetrievalEntry> selectedEntries,
                                  int selectedChars) {
        private ScenarioResult {
            expectedRelevantRefs = expectedRelevantRefs == null ? List.of() : List.copyOf(expectedRelevantRefs);
            selectedRefs = selectedRefs == null ? List.of() : List.copyOf(selectedRefs);
            selectedEntries = selectedEntries == null ? List.of() : List.copyOf(selectedEntries);
        }

        private long hitCount() {
            Set<String> selected = new HashSet<>(selectedRefs);
            return expectedRelevantRefs.stream().filter(selected::contains).distinct().count();
        }

        private double reciprocalRank() {
            Set<String> expected = Set.copyOf(expectedRelevantRefs);
            for (int index = 0; index < selectedRefs.size(); index++) {
                if (expected.contains(selectedRefs.get(index))) {
                    return 1d / (index + 1);
                }
            }
            return 0d;
        }
    }
}
