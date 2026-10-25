package com.specagent.retrieval.eval;

import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.RetrievalScope;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:P3AChineseLexicalLaneEvaluationTest.java
 *
 * 测试目标:P3-A A 层——中文词法通道的确定性评测关卡。
 *
 * 背景(实测缺陷):{@code retrieval_entries.search_vector} 长期由
 * {@code to_tsvector('simple', content)} 生成,而 {@code simple} 配置没有中文
 * 分词器,会把每个由标点/空白分隔的中文连续串保留成「整句一个 token」:
 *
 *   to_tsvector('simple','用户取消后不得继续追加回答正文。规则编号 REQ-107。')
 *     -> '-107':4 'req':3 '用户取消后不得继续追加回答正文':1 '规则编号':2
 *
 * 因此任何非「逐字节等于整句」的中文查询都匹配不到(实测在冻结 52 条校准集上,
 * 普通中文改写的命中数为 0),而 ASCII 标识符因为分词器会切开非中文而不受影响。
 *
 * 本评测同时固定三件事:
 *  1. 修复后的中文词法通道确实能召回中文改写(正向证据);
 *  2. ASCII/标识符语义未被改变(回归证据);
 *  3. 单字噪声不会把无关中文大量拉进结果,即筛选仍有区分度(精度证据)。
 *
 * 统计单位是 source(一个 source_ref 一条),按 sourceRef 去重,避免同一来源的
 * 多个 chunk 抬高指标。
 */
@SpringBootTest(properties = {"spec.agent.retrieval.embedding.provider=fake", "spec.agent.retrieval.engine=java-hybrid.v1"})
@ActiveProfiles("test")
@Transactional
class P3AChineseLexicalLaneEvaluationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RetrievalEntryRepository entryRepository;
    @Autowired
    private HybridRetriever hybridRetriever;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 冻结的中文/ASCII 查询与它们必须能召回的唯一来源。 */
    private record QueryCase(String name, String query, String expectedText, String category) {
    }

    /**
     * 与 docs/v2/evidence/SHARED_RAG_52_QUERY_EVALUATION.json 同类的场景:
     * 中文改写、中文整句、ASCII 标识符、以及一条无关中文(必须不被召回)。
     */
    private static final List<QueryCase> CASES = List.of(
            new QueryCase("zh-paraphrase-cancel", "终止对话后的正文有什么约束？",
                    "用户取消后不得继续追加回答正文。规则编号 REQ-107。", "CHINESE_PARAPHRASE"),
            new QueryCase("zh-paraphrase-stop", "按了停止还会继续显示答案吗？",
                    "用户取消后不得继续追加回答正文。规则编号 REQ-107。", "CHINESE_PARAPHRASE"),
            new QueryCase("zh-exact-sentence", "用户取消后不得继续追加回答正文。规则编号 REQ-107。",
                    "用户取消后不得继续追加回答正文。规则编号 REQ-107。", "CHINESE_EXACT"),
            new QueryCase("ascii-identifier", "REQ-107",
                    "用户取消后不得继续追加回答正文。规则编号 REQ-107。", "EXACT_IDENTIFIER"),
            new QueryCase("zh-paraphrase-export", "把订单下载成表格该选哪种格式？",
                    "订单导出格式为 CSV，UTF-8 编码。接口编号 REQ-100。", "CHINESE_PARAPHRASE"),
            new QueryCase("ascii-identifier-100", "REQ-100",
                    "订单导出格式为 CSV，UTF-8 编码。接口编号 REQ-100。", "EXACT_IDENTIFIER"));

    @Test
    void chineseLexicalLaneRecallsParaphrasesWithoutChangingAsciiSemantics() {
        Project project = projectService.createProject("p3a-zh-lexical-" + UUID.randomUUID());
        UUID route = project.activeRouteId();

        // 两个来源:目标事实 + 一条刻意无关的中文干扰项。
        Node cancellation = nodeService.createRootNode(project.id(), route,
                "用户取消后不得继续追加回答正文。规则编号 REQ-107。", null, List.of(), true);
        Node export = nodeService.createChildNode(project.id(), route, cancellation.id(),
                "订单导出格式为 CSV，UTF-8 编码。接口编号 REQ-100。", null, List.of(), true);
        Node unrelated = nodeService.createChildNode(project.id(), route, export.id(),
                "会议时长要求是什么？", null, List.of(), true);

        Map<String, UUID> refByText = Map.of(
                "用户取消后不得继续追加回答正文。规则编号 REQ-107。", cancellation.id(),
                "订单导出格式为 CSV，UTF-8 编码。接口编号 REQ-100。", export.id());

        List<String> failures = new ArrayList<>();
        int recalled = 0;
        int noiseHits = 0;
        List<Long> timings = new ArrayList<>();

        for (QueryCase testCase : CASES) {
            long started = System.nanoTime();
            // 只走 lexical 通道:关闭 trigram/vector 干扰,使本用例归因明确。
            List<RetrievalEntry> results = entryRepository.lexical(
                    project.id(), testCase.query(), 8, null, List.of());
            timings.add((System.nanoTime() - started) / 1_000_000L);

            Set<String> distinctRefs = new LinkedHashSet<>();
            for (RetrievalEntry entry : results) {
                distinctRefs.add(entry.sourceRef());
            }

            UUID expectedId = refByText.get(testCase.expectedText());
            boolean hit = distinctRefs.contains("node:" + expectedId);
            boolean unrelatedLeaked = distinctRefs.contains("node:" + unrelated.id());

            if (hit) {
                recalled++;
            } else {
                failures.add(testCase.name() + " (query=" + testCase.query()
                        + ") did not recall node:" + expectedId
                        + "; got " + distinctRefs);
            }
            if (unrelatedLeaked) {
                noiseHits++;
            }
        }

        // 采集可复跑的指标产物(不提交 build/ 目录)。
        long hitAt8 = recalled;
        double hitRate = (double) hitAt8 / CASES.size();
        timings.sort(Long::compareTo);
        long p50 = timings.isEmpty() ? 0 : timings.get(timings.size() / 2);
        long p95 = timings.isEmpty() ? 0 : timings.get((int) Math.ceil(timings.size() * 0.95) - 1);
        String artifact = "{\n"
                + "  \"layer\": \"A\",\n"
                + "  \"suite\": \"p3a-chinese-lexical-lane\",\n"
                + "  \"unit\": \"source (deduplicated by sourceRef)\",\n"
                + "  \"caseCount\": " + CASES.size() + ",\n"
                + "  \"recalled\": " + recalled + ",\n"
                + "  \"hitRateAt8\": " + String.format(Locale.ROOT, "%.4f", hitRate) + ",\n"
                + "  \"unrelatedNoiseHits\": " + noiseHits + ",\n"
                + "  \"p50Ms\": " + p50 + ",\n"
                + "  \"p95Ms\": " + p95 + "\n"
                + "}\n";
        try {
            java.nio.file.Path output = java.nio.file.Path.of(
                    "build", "eval-p3a", "layer-a-chinese-lexical.json");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.writeString(output, artifact);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("P3-A layer A artifact could not be written", ex);
        }

        // 硬性要求:中文改写必须能召回目标来源。
        assertThat(failures)
                .as("P3-A A-layer Chinese lexical failures: %s", failures)
                .isEmpty();
        assertThat(recalled).isEqualTo(CASES.size());
    }

    /**
     * 精度边界(如实记录,不掩盖):
     *
     * bigram 词法通道的召回增益来自"单个共享 bigram"(中文改写与原文往往只共享
     * 一两个双字词),因此它也会让只共享常见疑问词(如"什么")的无关中文命中。
     * 这条用例把这个代价固定下来,并证明它是有界的:真正无关的查询(零共享
     * bigram)仍然返回空。
     *
     * 冻结的 52 条校准集上,这个代价是可测的零:修复前后 no-answer 零命中正确率
     * 都是 0.8333(见 {@code build/eval-p3a/layer-a-retrieval.json})。
     */
    @Test
    void cjkLexicalLaneKeepsBoundedPrecisionForCompletelyUnrelatedQueries() {
        Project project = projectService.createProject("p3a-precision-" + UUID.randomUUID());
        UUID route = project.activeRouteId();
        nodeService.createRootNode(project.id(), route,
                "会议时长要求是什么？", null, List.of(), true);

        // 零共享 bigram 的中文查询:必须返回空,不能被 bigram 通道拉出噪声。
        List<RetrievalEntry> zeroOverlap = entryRepository.lexical(
                project.id(), "火星轨道探测器的推进剂储量是多少", 8, null, List.of());
        assertThat(zeroOverlap)
                .as("a query sharing no CJK bigram must not match")
                .isEmpty();

        // 已知代价:只共享常见疑问词的中文查询会命中。记录而非断言为零。
        List<RetrievalEntry> commonParticleOverlap = entryRepository.lexical(
                project.id(), "备份保留多长时间是什么规则", 8, null, List.of());
        assertThat(commonParticleOverlap)
                .as("recorded residual: common-bigram overlap can retrieve unrelated text")
                .isNotNull();
    }

    /**
     * 回归:ASCII 语义必须与修复前完全一致。
     * {@code cjk_tsquery} 对不含中文的输入必须退化成与
     * {@code plainto_tsquery('simple', ...)} 等价的行为——包括"只匹配完整 token"
     * 这一严格性,不能因为引入 bigram 就放宽成子串匹配。
     */
    @Test
    void asciiQuerySemanticsStayStrictAfterCjkAugmentation() {
        Project project = projectService.createProject("p3a-ascii-strict-" + UUID.randomUUID());
        UUID route = project.activeRouteId();
        nodeService.createRootNode(project.id(), route,
                "接口编号 REQ-1071 的说明。", null, List.of(), true);

        Integer originalSemantics = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retrieval_entries "
                        + "WHERE project_id = ? AND retracted_at IS NULL "
                        + "AND search_vector @@ plainto_tsquery('simple', ?)",
                Integer.class, project.id(), "REQ-107");
        Integer augmentedSemantics = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retrieval_entries "
                        + "WHERE project_id = ? AND retracted_at IS NULL "
                        + "AND search_vector @@ cjk_tsquery(?)",
                Integer.class, project.id(), "REQ-107");

        assertThat(augmentedSemantics)
                .as("an ASCII query must not start matching token prefixes")
                .isEqualTo(originalSemantics);
    }

    /**
     * 通过真实混合检索器验证修复在完整链路上生效:中文改写能召回目标来源,
     * 且结果仍受 scope/项目隔离约束。
     */
    @Test
    void hybridRetrieverRecallsChineseParaphrase() {
        Project project = projectService.createProject("p3a-zh-hybrid-" + UUID.randomUUID());
        UUID route = project.activeRouteId();
        Node target = nodeService.createRootNode(project.id(), route,
                "数据留存要求是180天。", null, List.of(), true);

        List<HybridRetriever.Candidate> candidates = hybridRetriever.retrieve(new RetrievalQuery(
                project.id(), route, target.id(), "PROJECT", "数据要保存多久？",
                List.of(RetrievalScope.ROUTE, RetrievalScope.PROJECT), 8, 8_000,
                Set.of("node:" + target.id()), Set.of(target.id())));

        assertThat(candidates)
                .as("hybrid retrieval must recall the Chinese retention fact")
                .anySatisfy(candidate -> assertThat(candidate.entry().sourceRef())
                        .isEqualTo("node:" + target.id()));
        assertThat(candidates)
                .allSatisfy(candidate -> assertThat(candidate.entry().projectId())
                        .isEqualTo(project.id()));
    }
}
