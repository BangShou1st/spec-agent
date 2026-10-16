package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.RetrievalScope;
import com.specagent.retrieval.search.HybridRetriever;
import com.specagent.workspace.node.KnowledgeStatus;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeAuthorKind;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:P3ARetrievalLayerAEvaluationTest.java
 *
 * 测试目标:P3-A A 层——用**冻结的**中文检索校准集
 * ({@code contracts/retrieval/evaluation/chinese-queries.v1.json},52 条 AI 编写、
 * 待人工审阅)在确定性、无真实模型调用的条件下评测项目 Agent 的"找得到吗"。
 *
 * 与既有评测的分工(刻意不重复):
 *  - {@code SharedRetrievalIntegrationTest} 跑 {@code python-rag.v1} + 本地 Ollama,
 *    产出 recallAt8/mrr(需要真实向量服务)。
 *  - 本套件跑**默认本地安装**的 {@code java-hybrid.v1} 通道。默认装配用
 *    {@code NoopEmbeddingGateway},向量通道恒空,所以这里测的正是没有向量服务时
 *    项目 Agent 真实拿到的检索能力。
 *
 * 前后对照的严谨性(本套件的关键设计):
 *  两边都跑**真实的 HybridRetriever 融合**(词法+trigram+graph+RRF),唯一差别是
 *  词法谓词。做法是在同一事务内把 cjk_tsquery 临时替换成修复前的
 *  plainto_tsquery 语义再跑一遍,然后恢复。这样"提升来自通用词法修复"就不是靠
 *  换统计口径得出的结论。PostgreSQL 支持事务性 DDL,替换随事务回滚,不留副作用。
 *
 * 统计纪律:
 *  - 统计单位是 source:同一来源去重后只计一次;
 *  - 无答案样本单独统计零命中正确率,不混入 Recall 分母;
 *  - 多来源问题额外统计 All-required@5;
 *  - 越界/过期来源单独计数,不允许用它们换召回。
 */
@SpringBootTest(properties = "spec.agent.retrieval.embedding.provider=fake")
@ActiveProfiles("test")
@Transactional
class P3ARetrievalLayerAEvaluationTest {

    private static final Path DATASET = Path.of(
            "..", "contracts", "retrieval", "evaluation", "chinese-queries.v1.json");

    private static final String LEGACY_FUNCTION = """
            CREATE OR REPLACE FUNCTION cjk_tsquery(txt text) RETURNS tsquery AS $$
            BEGIN
                RETURN plainto_tsquery('simple', txt);
            END
            $$ LANGUAGE plpgsql IMMUTABLE
            """;

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private HybridRetriever hybridRetriever;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    private record LoadedSource(boolean foreignProject, UUID nodeId) {
    }

    /** 一次完整运行的指标。 */
    private record Metrics(double recallAt5,
                           double recallAt8,
                           double hitAt5,
                           double mrrAt5,
                           double allRequiredAt5,
                           long zeroHitCorrect,
                           long noAnswer,
                           long permissionLeaks,
                           long staleLeaks,
                           List<Observation> observations) {
    }

    private record Observation(String id,
                               String category,
                               String query,
                               List<String> expected,
                               List<String> retrieved) {
    }

    @Test
    void frozenChineseCalibrationSetMeasuresDefaultInstallRetrievalQuality() throws Exception {
        JsonNode dataset = mapper.readTree(Files.readString(DATASET));
        assertThat(dataset.path("datasetRole").asText())
                .as("this suite replays the frozen development calibration set")
                .isEqualTo("DEVELOPMENT_CALIBRATION");

        Project project = projectService.createProject("p3a-layer-a-" + UUID.randomUUID());
        Project other = projectService.createProject("p3a-layer-a-other-" + UUID.randomUUID());

        Map<String, LoadedSource> aliases = new LinkedHashMap<>();
        Set<String> staleAliases = new LinkedHashSet<>();
        Set<UUID> foreignNodes = new LinkedHashSet<>();
        for (JsonNode source : dataset.path("sources")) {
            String kind = source.path("kind").asText();
            String alias = source.path("alias").asText();
            boolean foreign = "PRIVATE_NODE".equals(kind) || "STALE_NODE".equals(kind);
            UUID owner = foreign ? other.id() : project.id();
            Node node = nodeService.createFloatingWorkspaceNode(owner,
                    "RESOURCE".equals(kind) ? NodeKind.RESOURCE : NodeKind.KNOWLEDGE,
                    "RESOURCE".equals(kind) ? "TEXT" : "NOTE",
                    Map.of("text", source.path("text").asText()),
                    NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
            aliases.put(alias, new LoadedSource(foreign, node.id()));
            if (foreign) {
                foreignNodes.add(node.id());
            }
            if ("STALE_NODE".equals(kind)) {
                staleAliases.add(alias);
                // 过期来源:标记撤回但不刷新派生投影——规范校验仍必须拒绝它。
                jdbcTemplate.update("UPDATE nodes SET retracted_at = now() WHERE id = ?", node.id());
            }
        }
        assertThat(aliases).isNotEmpty();

        // ---- 第 1 遍:修复后的词法谓词(当前生产语义) ----
        Metrics after = measure(dataset, project, aliases, foreignNodes, staleAliases);

        // ---- 第 2 遍:临时把 cjk_tsquery 退回修复前的 plainto_tsquery 语义 ----
        // 真实融合链路不变,只改词法谓词,因此两遍之差就是这次修复的效果。
        // 先抓取当前定义再从数据库恢复,避免依赖迁移文件文本。
        String productionDefinition = jdbcTemplate.queryForObject(
                "SELECT pg_get_functiondef('cjk_tsquery(text)'::regprocedure)", String.class);
        try {
            jdbcTemplate.execute(LEGACY_FUNCTION);
            Metrics before = measure(dataset, project, aliases, foreignNodes, staleAliases);
            applyReport(dataset, after, before);
        } finally {
            jdbcTemplate.execute(productionDefinition);
        }
    }

    private void applyReport(JsonNode dataset, Metrics after, Metrics before) throws Exception {

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("layer", "A");
        report.put("suite", "p3a-retrieval-default-install");
        report.put("engine", "java-hybrid.v1");
        report.put("embeddingProvider", "fake");
        report.put("vectorLaneNote", "production default is provider=noop, so the vector lane is empty");
        report.put("datasetFile", "contracts/retrieval/evaluation/chinese-queries.v1.json");
        report.put("datasetRole", dataset.path("datasetRole").asText());
        report.put("authorship", dataset.path("authorship").asText());
        report.put("humanReviewStatus", dataset.path("humanReviewStatus").asText());
        report.put("labelCaveat",
                "AI-authored, human review PENDING; not independent human-labelled acceptance");
        report.put("unit", "source (deduplicated by sourceRef)");
        report.put("queryCount", after.observations().size());
        report.put("labelledQueries", after.observations().stream()
                .filter(observation -> !observation.expected().isEmpty()).count());
        report.put("noAnswerQueries", after.noAnswer());
        report.put("after", metricsMap(after));
        report.put("before", metricsMap(before));
        report.put("observations", after.observations().stream().map(observation -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", observation.id());
            item.put("category", observation.category());
            item.put("query", observation.query());
            item.put("expectedAliases", observation.expected());
            item.put("retrievedAliasesAfter", observation.retrieved());
            before.observations().stream()
                    .filter(candidate -> candidate.id().equals(observation.id()))
                    .findFirst()
                    .ifPresent(candidate -> item.put("retrievedAliasesBefore",
                            candidate.retrieved()));
            return item;
        }).toList());

        Path output = Path.of("build", "eval-p3a", "layer-a-retrieval.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));

        System.out.println("P3-A layer A written: " + output.toAbsolutePath());
        System.out.println("after : recall@5=" + after.recallAt5()
                + " recall@8=" + after.recallAt8()
                + " mrr@5=" + after.mrrAt5()
                + " allRequired@5=" + after.allRequiredAt5()
                + " zeroHit=" + ratio(after.zeroHitCorrect(), after.noAnswer()));
        System.out.println("before: recall@5=" + before.recallAt5()
                + " recall@8=" + before.recallAt8()
                + " mrr@5=" + before.mrrAt5()
                + " allRequired@5=" + before.allRequiredAt5()
                + " zeroHit=" + ratio(before.zeroHitCorrect(), before.noAnswer()));

        // 硬性边界:权限与过期来源泄漏永远是零。
        assertThat(after.permissionLeaks()).as("authorization must never leak").isZero();
        assertThat(after.staleLeaks()).as("stale sources must never be returned").isZero();
        // 分母准确。
        long labelled = after.observations().stream()
                .filter(observation -> !observation.expected().isEmpty()).count();
        assertThat(labelled + after.noAnswer()).isEqualTo(after.observations().size());
        assertThat(labelled).isPositive();
        assertThat(after.noAnswer()).isPositive();
        // 本次修复必须带来非退化:召回不下降、且至少在一个类别上提升。
        assertThat(after.recallAt5())
                .as("the CJK lexical fix must not reduce Recall@5")
                .isGreaterThanOrEqualTo(before.recallAt5());
        assertThat(after.allRequiredAt5())
                .as("the CJK lexical fix must improve full-coverage retrieval")
                .isGreaterThan(before.allRequiredAt5());
    }

    private Metrics measure(JsonNode dataset,
                            Project project,
                            Map<String, LoadedSource> aliases,
                            Set<UUID> foreignNodes,
                            Set<String> staleAliases) {
        long labelled = 0;
        long noAnswer = 0;
        long zeroHitCorrect = 0;
        long permissionLeaks = 0;
        long staleLeaks = 0;
        double recall5 = 0;
        double recall8 = 0;
        double hit5 = 0;
        double mrr5 = 0;
        double allRequired5 = 0;
        List<Observation> observations = new ArrayList<>();

        for (JsonNode query : dataset.path("queries")) {
            String queryText = query.path("query").asText();
            List<String> expected = textList(query.path("expectedAliases"));
            List<String> forbidden = textList(query.path("forbiddenAliases"));

            List<HybridRetriever.Candidate> candidates = hybridRetriever.retrieve(new RetrievalQuery(
                    project.id(), project.activeRouteId(), null, "PROJECT", queryText,
                    List.of(RetrievalScope.ROUTE, RetrievalScope.PROJECT, RetrievalScope.RESOURCE),
                    8, 8_000, Set.of(), Set.of()));

            // 统计单位 = source:按来源去重,保留首次出现的顺序。
            List<String> retrieved = new ArrayList<>();
            for (HybridRetriever.Candidate candidate : candidates) {
                UUID nodeId = nodeIdOf(candidate.entry().sourceRef());
                String alias = aliasOf(aliases, nodeId);
                if (alias != null && !retrieved.contains(alias)) {
                    retrieved.add(alias);
                }
                if (nodeId != null && foreignNodes.contains(nodeId)) {
                    // 跨项目来源绝不允许出现在当前项目的结果里。
                    permissionLeaks++;
                }
            }
            for (String alias : retrieved) {
                if (staleAliases.contains(alias)) {
                    staleLeaks++;
                }
                if (forbidden.contains(alias)) {
                    permissionLeaks++;
                }
            }

            observations.add(new Observation(query.path("id").asText(),
                    query.path("category").asText(), queryText, expected, retrieved));

            List<String> top5 = retrieved.subList(0, Math.min(5, retrieved.size()));
            List<String> top8 = retrieved.subList(0, Math.min(8, retrieved.size()));

            if (expected.isEmpty()) {
                noAnswer++;
                if (retrieved.isEmpty()) {
                    zeroHitCorrect++;
                }
            } else {
                labelled++;
                int covered5 = (int) expected.stream().filter(top5::contains).count();
                int covered8 = (int) expected.stream().filter(top8::contains).count();
                recall5 += (double) covered5 / expected.size();
                recall8 += (double) covered8 / expected.size();
                if (covered5 > 0) {
                    hit5 += 1;
                }
                if (covered5 == expected.size()) {
                    allRequired5 += 1;
                }
                for (int rank = 0; rank < top5.size(); rank++) {
                    if (expected.contains(top5.get(rank))) {
                        mrr5 += 1.0 / (rank + 1);
                        break;
                    }
                }
            }
        }

        return new Metrics(round(recall5 / labelled), round(recall8 / labelled),
                round(hit5 / labelled), round(mrr5 / labelled),
                round(allRequired5 / labelled), zeroHitCorrect, noAnswer,
                permissionLeaks, staleLeaks, List.copyOf(observations));
    }

    private static Map<String, Object> metricsMap(Metrics metrics) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("recallAt5", metrics.recallAt5());
        map.put("recallAt8", metrics.recallAt8());
        map.put("hitAt5", metrics.hitAt5());
        map.put("mrrAt5", metrics.mrrAt5());
        map.put("allRequiredAt5", metrics.allRequiredAt5());
        map.put("zeroHitCorrect", metrics.zeroHitCorrect());
        map.put("noAnswerQueries", metrics.noAnswer());
        map.put("zeroHitCorrectness", ratio(metrics.zeroHitCorrect(), metrics.noAnswer()));
        map.put("permissionLeaks", metrics.permissionLeaks());
        map.put("staleLeaks", metrics.staleLeaks());
        return map;
    }

    private static Object ratio(long numerator, long denominator) {
        return denominator == 0 ? null : round((double) numerator / denominator);
    }

    /**
     * 从 sourceRef 解析承载它的规范节点 id。
     *
     * 资源内容有两种形态:非共享路径是 {@code resource-chunk:<nodeId>:<index>},
     * 共享 Python 路径可能是 {@code node:<id>}。两者都归一到资源节点,否则按 ref
     * 统计会把已召回的 resource chunk 误判为未命中。
     */
    private static UUID nodeIdOf(String sourceRef) {
        if (sourceRef == null) {
            return null;
        }
        String raw = null;
        if (sourceRef.startsWith("node:")) {
            raw = sourceRef.substring("node:".length());
        } else if (sourceRef.startsWith("resource-chunk:")) {
            String remainder = sourceRef.substring("resource-chunk:".length());
            int separator = remainder.indexOf(':');
            raw = separator < 0 ? remainder : remainder.substring(0, separator);
        }
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String aliasOf(Map<String, LoadedSource> aliases, UUID nodeId) {
        if (nodeId == null) {
            return null;
        }
        return aliases.entrySet().stream()
                .filter(entry -> entry.getValue().nodeId().equals(nodeId))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.4f", value));
    }
}
