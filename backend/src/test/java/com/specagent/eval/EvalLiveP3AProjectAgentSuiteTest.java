package com.specagent.eval;

import com.specagent.eval.P3AClaimGrounding.AnswerRow;
import com.specagent.eval.P3AClaimGrounding.ClaimView;
import com.specagent.eval.P3AClaimGrounding.NodeRow;
import com.specagent.eval.P3AClaimGrounding.PatchSource;
import com.specagent.eval.P3AClaimGrounding.RouteRow;
import com.specagent.eval.P3AClaimGrounding.SourceCatalog;
import com.specagent.eval.P3ADecisionScoring.DecisionObservation;
import com.specagent.eval.P3ADecisionScoring.PayloadValidity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:EvalLiveP3AProjectAgentSuiteTest.java
 *
 * 测试目标:P3-A B 层与 C 层的**真实模型**评测套件——用生产链路
 * (Java Runtime + 远程 Python Brain + 产品自己配置的模型)跑一组通用需求
 * 场景,分别回答两个问题:
 *
 *   B. 理解得对吗?——STATE_UPDATE 是否提取出用户明确表达的事实、条件、否定,
 *      是否保留未决事项,是否把无依据内容标成 CONFIRMED。
 *   C. 下一步决策合理吗?——DECISION 选中的动作族是否落在该场景声明的
 *      acceptable family 集合内,payload 是否通过 Java 契约校验。
 *
 * 关键纪律:
 *  - B 与 C 分开记分,绝不合成一个"AI 效果分";
 *  - C 层四个量严格分开:{@link P3ADecisionScoring} 分别报告
 *    Action acceptable-set accuracy / Payload schema validity /
 *    Runtime execution success / INFRA;
 *  - 真实模型输出与 deterministic 结果严格区分:本套件不安装任何脚本化 Brain,
 *    {@link LiveExecutionGuard} 会强制校验 bean 身份;
 *  - 真正的网络/Provider 失败走 {@link LiveFailureClassifier} 判定为 INFRA,
 *    **不计为决策错误**;而模型输出契约违约(非 JSON、额外字段、payload 形状)
 *    属于**模型协议质量**问题,单独分类,绝不合并进 INFRA 从而被掩盖;
 *  - CONFIRMED claim 的"无依据"判定使用 {@link P3AClaimGrounding}:读取真实
 *    来源字段(node/answer/project/route)并按一致性校验,不再只看文本是否为空;
 *  - 标签是 AI 编写的候选标签,全部标记 PENDING_HUMAN_REVIEW,绝不声称人工验收。
 *
 * 数据来源:{@code tools/p3a/p3a_scenarios.json}(AI 编写候选场景与候选标签)。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18084",
                "spec.agent.retrieval.engine=java-hybrid.v1",
                "spec.agent.brain.engine=remote-python",
                "spec.agent.brain.base-url=${SPEC_AGENT_EVAL_BRAIN_BASE_URL:http://localhost:8100}",
                "spec.agent.brain.internal-secret=${SPEC_AGENT_BRAIN_INTERNAL_SECRET:dev-internal-secret}",
                "spec.agent.model.inference=opencode",
                "spec.agent.model.runtime-settings-source=database",
                "spec.agent.semantic-trace.enabled=true",
                // An isolated database is REQUIRED: this suite drives the real model and
                // must never write agent runs into a database holding user projects.
                "spring.datasource.url=${SPEC_AGENT_EVAL_DB_URL:jdbc:postgresql://localhost:5434/spec_agent_p3a}",
                // The stored credential is encrypted under the install master key. An
                // EXPLICIT (blank) override makes ModelCredentialCrypto fall back to the
                // key file, so the isolated DB's credential can be decrypted without
                // copying any key material into source or environment.
                "spec.agent.secret.master-key=",
                "spec.agent.secret.master-key-file=${SPEC_AGENT_EVAL_MASTER_KEY_FILE:./data/secret-master.key}"
        })
class EvalLiveP3AProjectAgentSuiteTest extends EvalLiveHarnessBase {

    private static final Path SCENARIOS = Path.of(
            "..", "tools", "p3a", "p3a_scenarios.json");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void realModelProjectAgentScoresUnderstandingAndDecisionSeparately() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var corpus = objectMapper.readTree(Files.readString(SCENARIOS));
        var scenarios = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        corpus.path("scenarios").forEach(scenarios::add);

        // 只评测前 8 个场景:真实模型调用有成本,首轮保持小规模、有代表性。
        int limit = Math.min(scenarios.size(), 8);
        List<Map<String, Object>> observations = new ArrayList<>();
        List<DecisionObservation> decisionObservations = new ArrayList<>();
        long ungroundedTotal = 0;

        for (int index = 0; index < limit; index++) {
            var scenario = scenarios.get(index);
            String scenarioId = scenario.path("id").asText();
            String question = scenario.path("question").asText();
            String answer = scenario.path("answer").asText();
            List<String> acceptable = new ArrayList<>();
            scenario.path("acceptableActions").forEach(node -> acceptable.add(node.asText()));

            var record = new LinkedHashMap<String, Object>();
            record.put("scenarioId", scenarioId);
            record.put("family", scenario.path("family").asText());
            record.put("question", question);
            record.put("answer", answer);
            record.put("acceptableActions", acceptable);
            record.put("labelReview", "PENDING_HUMAN_REVIEW");
            record.put("labelAuthorship", "AI-authored candidate label");

            // 用生产运行时搭图:真实中文问题 + 真实中文回答。
            var given = new GivenSpec(
                    "p3a-live-" + scenarioId,
                    List.of(new GraphStep.CreateRootQuestionText(question, true)),
                    new UserEvent.AnswerTipText(answer),
                    new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                    new FocusContextSpec(null, null),
                    List.of(),
                    List.of(),
                    new BrainScript(List.of(), new BrainDecision("WAIT", Map.of()),
                            List.of(), List.of()));
            var definition = new ScenarioDefinition(scenarioId, "1",
                    "P3-A live B/C scenario", given,
                    new ExpectSpec(
                            java.util.Set.of("GRAPH_INTEGRITY"),
                            List.of(),
                            java.util.Set.of(),
                            java.util.Set.of(),
                            Map.of(), Map.of(),
                            // Live behavior is recorded, not gated: allow the conflict-repair
                            // second DECISION call so a legitimate repair is not miscounted.
                            new CallBudget(List.of("STATE_UPDATE", "DECISION"), 1, 4, 2, 0, 5, 0)),
                    List.of(VariantSpec.base("base", 9000L + index)));

            long started = System.nanoTime();
            ObservationEnvelope observation;
            try {
                observation = scenarioRunner.runLive(definition,
                        definition.variants().get(0), 9000L + index);
            } catch (RuntimeException ex) {
                record.put("outcome", "INFRA");
                record.put("infraClass", "TEST_HARNESS_SETUP");
                record.put("detail", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                observations.add(record);
                decisionObservations.add(new DecisionObservation(scenarioId, null, false,
                        PayloadValidity.NOT_MEASURED, false, true,
                        "TEST_HARNESS_SETUP", null));
                continue;
            }
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            record.put("latencyMs", latencyMs);
            record.put("executionResult", observation.executionResult());
            record.put("actualPrimaryAction", observation.actualPrimaryAction());
            record.put("productionModelCalls", observation.productionModelCalls());
            record.put("providerRetries", observation.providerRetries());

            String failure = observation.executionResult();
            String actualAction = observation.actualPrimaryAction();

            // 模型协议违约与真正的 INFRA 分开判定:
            //  - 契约/JSON/字段违约 = 模型输出质量问题(绝不并入 INFRA);
            //  - 网络/provider/超时 = INFRA(不计为决策错误)。
            String protocolClass = P3ADecisionScoring.classifyModelProtocolFailure(
                    failure, traceErrorTypes(observation));
            boolean modelProtocolFailure = protocolClass != null;
            boolean infra = !modelProtocolFailure
                    && LiveFailureClassifier.isInfrastructureFailure(observation);
            if (infra) {
                record.put("outcome", "INFRA");
                record.put("infraClass", infraClass(observation));
                record.put("detail", failure);
                observations.add(record);
                decisionObservations.add(new DecisionObservation(scenarioId, null, false,
                        PayloadValidity.NOT_MEASURED, false, true,
                        infraClass(observation), null));
                continue;
            }

            // ---- B 层:读回持久化的 claims(真实 STATE_UPDATE 产物) ----
            // 读回**来源字段**(此前被丢弃),用确定性 grounding 校验。
            UUID projectId = scenarioRunner.lastProjectId();
            SourceCatalog catalog = jdbcSourceCatalog();
            List<Map<String, Object>> patchRows = jdbcTemplate.queryForList("""
                    SELECT project_id, route_id, source_node_id, source_answer_id, claims
                    FROM answer_patches WHERE project_id = ? ORDER BY created_at
                    """, projectId);
            List<Map<String, Object>> claims = new ArrayList<>();
            long ungrounded = 0;
            for (Map<String, Object> row : patchRows) {
                Object raw = row.get("claims");
                if (raw == null) {
                    continue;
                }
                var parsed = objectMapper.readTree(String.valueOf(raw));
                List<ClaimView> patchClaims = new ArrayList<>();
                parsed.forEach(claim -> {
                    ClaimView view = new ClaimView(
                            claim.path("kind").asText(),
                            claim.path("status").asText(),
                            claim.path("text").asText(),
                            uuidOrNull(claim.path("sourceNodeId")),
                            uuidOrNull(claim.path("sourceAnswerId")));
                    patchClaims.add(view);
                    claims.add(Map.of("kind", view.kind(), "status", view.status(),
                            "text", view.text()));
                });
                PatchSource patch = new PatchSource(
                        asUuid(row.get("project_id")),
                        asUuid(row.get("route_id")),
                        asUuid(row.get("source_node_id")),
                        asUuid(row.get("source_answer_id")));
                ungrounded += P3AClaimGrounding
                        .ungroundedConfirmed(patch, patchClaims, catalog).size();
            }
            ungroundedTotal += ungrounded;
            record.put("claims", claims);
            record.put("ungroundedConfirmed", ungrounded);
            record.put("bLayer", scoreClaims(objectMapper, scenario, claims));

            // ---- C 层:acceptable family 集合 + payload 协议校验 + 执行成功 ----
            boolean actionAcceptable = actualAction != null && acceptable.contains(actualAction);
            boolean executionSuccess = "completed".equals(observation.executionResult());
            PayloadValidity payloadValidity = P3ADecisionScoring.payloadValidity(
                    actualAction, protocolClass);
            var cLayer = new LinkedHashMap<String, Object>();
            cLayer.put("actionAcceptable", actionAcceptable);
            cLayer.put("actionMeasured", actualAction != null);
            cLayer.put("payloadValidity", payloadValidity.name());
            cLayer.put("executionSuccess", executionSuccess);
            cLayer.put("protocolFailureClass", protocolClass == null ? "NONE" : protocolClass);
            record.put("cLayer", cLayer);
            record.put("outcome", "COMPLETED");
            observations.add(record);
            decisionObservations.add(new DecisionObservation(
                    scenarioId, actualAction, actionAcceptable, payloadValidity,
                    executionSuccess, false, null, protocolClass));
            System.out.println("P3-A live " + scenarioId + ": action="
                    + actualAction + " claims=" + claims.size()
                    + " acceptable=" + actionAcceptable + " payload=" + payloadValidity
                    + " ungrounded=" + ungrounded + " latency=" + latencyMs + "ms");
        }

        var completed = observations.stream()
                .filter(item -> "COMPLETED".equals(item.get("outcome"))).toList();
        var infra = observations.stream()
                .filter(item -> "INFRA".equals(item.get("outcome"))).toList();

        long forbidden = completed.stream()
                .mapToLong(item -> ((List<?>) ((Map<?, ?>) item.get("bLayer"))
                        .get("forbiddenViolations")).size()).sum();
        long unknownAsserted = completed.stream()
                .mapToLong(item -> ((List<?>) ((Map<?, ?>) item.get("bLayer"))
                        .get("unknownAssertedConfirmed")).size()).sum();
        List<Long> latencies = completed.stream()
                .map(item -> ((Number) item.get("latencyMs")).longValue()).sorted().toList();
        long executionCompleted = completed.stream()
                .filter(item -> "completed".equals(item.get("executionResult"))).count();

        var cSummary = P3ADecisionScoring.summarize(decisionObservations);

        var report = new LinkedHashMap<String, Object>();
        report.put("recordedAt", Instant.now().toString());
        report.put("layer", "B+C");
        report.put("suite", "p3a-live-project-agent");
        report.put("boundary", "real project Agent production chain: Java runtime + remote "
                + "Python brain (broker) + product-configured model; no scripted brain, "
                + "no mocked model output");
        report.put("gitSha", gitSha());
        report.put("corpusFile", "tools/p3a/p3a_scenarios.json");
        report.put("labelProvenance", corpus.path("authorship").asText());
        report.put("humanReviewStatus", corpus.path("humanReviewStatus").asText());
        report.put("labelReview", "PENDING_HUMAN_REVIEW");
        report.put("environment", Map.of(
                "brain", "remote-python-broker",
                "retrievalEngine", "java-hybrid.v1",
                "modelSource", "product database opencode_settings",
                "database", "isolated P3-A evaluation database"));
        report.put("plannedScenarios", limit);
        report.put("evaluated", completed.size());
        report.put("executionCompleted", executionCompleted);
        report.put("infraFailed", infra.size());
        report.put("bLayer", Map.of(
                "claimsProduced", completed.stream()
                        .mapToLong(item -> ((List<?>) item.get("claims")).size()).sum(),
                "forbiddenOvergeneralizations", forbidden,
                "unknownAssertedConfirmed", unknownAsserted,
                "ungroundedConfirmed", ungroundedTotal,
                "groundingMethod", "deterministic source validation "
                        + "(claim/patch source node+answer, project, route, lifecycle)"));
        var cLayer = new LinkedHashMap<String, Object>();
        cLayer.put("actionAcceptableSetAccuracy", cSummary.actionAcceptableSetAccuracy());
        cLayer.put("actionMeasuredCount", cSummary.actionMeasuredCount());
        cLayer.put("payloadValidity", cSummary.payloadValidity());
        cLayer.put("payloadValidityMeasuredCount", cSummary.payloadValidityMeasuredCount());
        cLayer.put("payloadValidityNotMeasuredCount", cSummary.payloadValidityNotMeasuredCount());
        cLayer.put("executionSuccessRate", cSummary.executionSuccessRate());
        cLayer.put("evaluatedCount", cSummary.evaluated());
        cLayer.put("modelProtocolFailures", cSummary.modelProtocolFailures());
        cLayer.put("protocolFailureBreakdown", cSummary.protocolFailureBreakdown());
        cLayer.put("infraFailures", cSummary.infraFailures());
        cLayer.put("infraClassBreakdown", cSummary.infraClassBreakdown());
        cLayer.put("note", "action acceptable-set accuracy and payload validity are "
                + "independent measures with their own measured-sample counts; neither "
                + "is a single 'overall decision accuracy'");
        report.put("cLayer", cLayer);
        var latency = new LinkedHashMap<String, Object>();
        latency.put("p50", latencies.isEmpty() ? null : latencies.get(latencies.size() / 2));
        latency.put("p95", latencies.isEmpty() ? null
                : latencies.get(Math.min(latencies.size() - 1,
                (int) Math.ceil(latencies.size() * 0.95) - 1)));
        latency.put("max", latencies.isEmpty() ? null : latencies.get(latencies.size() - 1));
        report.put("latencyMs", latency);
        report.put("infrastructureFailures", infra.stream()
                .map(item -> Map.of("scenarioId", item.get("scenarioId"),
                        "infraClass", String.valueOf(item.get("infraClass")),
                        "detail", String.valueOf(item.get("detail")))).toList());
        report.put("observations", observations);

        Path output = Path.of(System.getProperty("user.dir"), "build", "eval-p3a",
                "layer-bc-live.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, EvalArtifactWriter.toJson(report), StandardCharsets.UTF_8);
        System.out.println("P3-A live B/C written: " + output.toAbsolutePath());
        System.out.println("evaluated=" + completed.size() + " infra=" + infra.size()
                + " actionAcc=" + cSummary.actionAcceptableSetAccuracy()
                + " payloadValidity=" + cSummary.payloadValidity()
                + " protocolFailures=" + cSummary.modelProtocolFailures()
                + " forbidden=" + forbidden
                + " unknownAssertedConfirmed=" + unknownAsserted
                + " ungroundedConfirmed=" + ungroundedTotal);

        // 非阻塞:记录真实行为基线,不放宽任何产品语义。
        // 唯一硬约束是"无依据的 CONFIRMED claim"必须为零——这是产品边界。
        org.assertj.core.api.Assertions.assertThat(ungroundedTotal)
                .as("no CONFIRMED claim may be persisted without grounding")
                .isZero();
    }

    /** 从语义追踪里取失败阶段记录的 error_type,用于区分协议违约与 INFRA。 */
    private static List<String> traceErrorTypes(ObservationEnvelope observation) {
        List<String> types = new ArrayList<>();
        observation.semanticTrace().stages().values().forEach(stage -> {
            Object value = stage.get("error_type");
            if (value != null) {
                types.add(String.valueOf(value));
            }
        });
        return types;
    }

    /** INFRA 细分类;复用既有分类器,保持与其它评测一致的证据口径。 */
    private static String infraClass(ObservationEnvelope observation) {
        var classified = LiveFailureClassifier.classify(observation);
        return classified == null ? "OTHER" : classified.name();
    }

    /** 只读来源目录:把 claim/patch 的来源 id 解析为真实存储行。 */
    private SourceCatalog jdbcSourceCatalog() {
        return new SourceCatalog() {
            @Override
            public AnswerRow findAnswer(UUID id) {
                if (id == null) {
                    return null;
                }
                List<AnswerRow> rows = jdbcTemplate.query(
                        "SELECT id, project_id, route_id, node_id FROM answers WHERE id = ?",
                        (rs, rowNum) -> new AnswerRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("project_id", UUID.class),
                                rs.getObject("route_id", UUID.class),
                                rs.getObject("node_id", UUID.class)),
                        id);
                return rows.isEmpty() ? null : rows.get(0);
            }

            @Override
            public NodeRow findNode(UUID id) {
                if (id == null) {
                    return null;
                }
                List<NodeRow> rows = jdbcTemplate.query(
                        "SELECT id, project_id FROM nodes WHERE id = ?",
                        (rs, rowNum) -> new NodeRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("project_id", UUID.class)),
                        id);
                return rows.isEmpty() ? null : rows.get(0);
            }

            @Override
            public RouteRow findRoute(UUID id) {
                if (id == null) {
                    return null;
                }
                List<RouteRow> rows = jdbcTemplate.query(
                        "SELECT id, project_id, lifecycle_status FROM routes WHERE id = ?",
                        (rs, rowNum) -> new RouteRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("project_id", UUID.class),
                                rs.getString("lifecycle_status")),
                        id);
                return rows.isEmpty() ? null : rows.get(0);
            }
        };
    }

    private static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID id) {
            return id;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : UUID.fromString(text);
    }

    private static UUID uuidOrNull(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : UUID.fromString(text);
    }

    /**
     * B 层评分:确定性语义条件,不做字符串相等。
     *
     * requiredConcepts 的每个 OR-group 必须被**同一条** claim 命中(接受改写,
     * 但拒绝把不同 claim 的关键词拼凑成一个概念);forbiddenOvergeneralizations
     * 命中即违规(例如把"所有预约都要审批"这类过度概括写进 claim);
     * mustNotSettle 的内容若被标成 CONFIRMED,说明把"未知"当成了"已确认"。
     *
     * 无依据的 CONFIRMED 不在这里判定——它由 {@link P3AClaimGrounding} 基于真实
     * 来源字段做确定性校验,避免只看文本是否为空。
     */
    private static Map<String, Object> scoreClaims(
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            com.fasterxml.jackson.databind.JsonNode scenario,
            List<Map<String, Object>> claims) {
        List<String> texts = claims.stream()
                .map(claim -> normalized(String.valueOf(claim.get("text")))).toList();

        List<String> missed = new ArrayList<>();
        int requiredCount = 0;
        for (var concept : scenario.path("requiredConcepts")) {
            requiredCount++;
            boolean satisfied = false;
            for (int index = 0; index < texts.size(); index++) {
                String text = texts.get(index);
                boolean claimSatisfiesAllGroups = true;
                for (var group : concept.path("anyOf")) {
                    List<String> alternatives = new ArrayList<>();
                    group.forEach(node -> alternatives.add(node.asText().toLowerCase(Locale.ROOT)));
                    if (alternatives.stream().noneMatch(text::contains)) {
                        claimSatisfiesAllGroups = false;
                        break;
                    }
                }
                if (claimSatisfiesAllGroups) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                missed.add(concept.path("id").asText());
            }
        }

        List<Map<String, Object>> forbiddenViolations = new ArrayList<>();
        for (var bad : scenario.path("forbiddenOvergeneralizations")) {
            List<String> parts = new ArrayList<>();
            bad.path("allOf").forEach(node -> parts.add(node.asText().toLowerCase(Locale.ROOT)));
            // A claim that explicitly REVOKES the superseded choice is the correct
            // answer, not an over-generalization. Without this guard a claim such as
            // "目标客户范围确定为连锁餐饮品牌，不再面向一般中小企业" would be falsely
            // flagged merely for mentioning the old option.
            List<String> unlessNegated = new ArrayList<>();
            bad.path("unlessNegated").forEach(
                    node -> unlessNegated.add(node.asText().toLowerCase(Locale.ROOT)));
            for (int index = 0; index < texts.size(); index++) {
                String text = texts.get(index);
                if (!parts.stream().allMatch(text::contains)) {
                    continue;
                }
                if (unlessNegated.stream().anyMatch(text::contains)) {
                    continue;
                }
                forbiddenViolations.add(Map.of("id", bad.path("id").asText(),
                        "claim", claims.get(index).get("text")));
                break;
            }
        }

        // "把未知说成已知" 的判定必须精确到**被settle的那个值**,而不是提到该主题。
        // 例如用户确实说了"大型会议室需要审批"(confirmed 正确),未定的只是
        // "按多少人算"。所以这里检查的是"settling 断言"模式,不是主题关键词。
        List<String> unknownAsserted = new ArrayList<>();
        for (var settled : scenario.path("mustNotSettle")) {
            List<String> patterns = new ArrayList<>();
            settled.path("patterns").forEach(
                    node -> patterns.add(node.asText().toLowerCase(Locale.ROOT)));
            for (int index = 0; index < texts.size(); index++) {
                String text = texts.get(index);
                if (patterns.stream().anyMatch(text::contains)
                        && "CONFIRMED".equals(claims.get(index).get("status"))) {
                    unknownAsserted.add(String.valueOf(claims.get(index).get("text")));
                }
            }
        }

        return Map.of(
                "claimCount", claims.size(),
                "requiredConcepts", requiredCount,
                "matchedRequired", requiredCount - missed.size(),
                "missedRequired", missed,
                "forbiddenViolations", forbiddenViolations,
                "unknownAssertedConfirmed", unknownAsserted);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String gitSha() {
        try {
            var process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            process.waitFor();
            return output.isEmpty() ? "unknown" : output;
        } catch (Exception ex) {
            return "unknown";
        }
    }
}
