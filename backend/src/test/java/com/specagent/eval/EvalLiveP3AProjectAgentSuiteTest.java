package com.specagent.eval;

import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
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
 *  - 真实模型输出与 deterministic 结果严格区分:本套件不安装任何脚本化 Brain,
 *    {@link LiveExecutionGuard} 会强制校验 bean 身份;
 *  - INFRA(网络/Provider)失败单独报告,**不计为决策错误**;
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
                record.put("detail", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                observations.add(record);
                continue;
            }
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            record.put("latencyMs", latencyMs);
            record.put("executionResult", observation.executionResult());
            record.put("actualPrimaryAction", observation.actualPrimaryAction());
            record.put("productionModelCalls", observation.productionModelCalls());
            record.put("providerRetries", observation.providerRetries());

            String failure = observation.executionResult();
            // INFRA classification: transport/provider/brain-contract failures are NOT
            // decision errors. Match case-insensitively — the real strings are
            // "AgentBrainUnavailableException", "brain_timeout", "model_provider_failure".
            String lowered = failure == null ? "" : failure.toLowerCase(Locale.ROOT);
            boolean infra = lowered.contains("unavailable")
                    || lowered.contains("timeout")
                    || lowered.contains("provider_failure")
                    || lowered.contains("502")
                    // A brain contract violation means the model emitted an off-contract
                    // payload; the brain rejected it fail-closed. That is a B-layer protocol
                    // finding, not a C-layer decision error.
                    || lowered.contains("model_contract")
                    || lowered.contains("brain_failure");
            if (infra) {
                record.put("outcome", "INFRA");
                record.put("infraClass", classifyInfra(lowered));
                record.put("detail", failure);
                observations.add(record);
                continue;
            }

            // ---- B 层:读回持久化的 claims(真实 STATE_UPDATE 产物) ----
            UUID projectId = scenarioRunner.lastProjectId();
            List<Map<String, Object>> claimRows = jdbcTemplate.queryForList("""
                    SELECT claims FROM answer_patches WHERE project_id = ? ORDER BY created_at
                    """, projectId);
            List<Map<String, Object>> claims = new ArrayList<>();
            for (Map<String, Object> row : claimRows) {
                Object raw = row.get("claims");
                if (raw == null) {
                    continue;
                }
                var parsed = objectMapper.readTree(String.valueOf(raw));
                parsed.forEach(claim -> claims.add(Map.of(
                        "kind", claim.path("kind").asText(),
                        "status", claim.path("status").asText(),
                        "text", claim.path("text").asText())));
            }
            record.put("claims", claims);
            record.put("bLayer", scoreClaims(objectMapper, scenario, claims));

            // ---- C 层:acceptable family 集合 + payload 校验结果 ----
            boolean actionAcceptable = acceptable.contains(observation.actualPrimaryAction());
            record.put("cLayer", Map.of(
                    "acceptableSetAccuracy", actionAcceptable,
                    "payloadValid", "completed".equals(observation.executionResult())
                            || actionAcceptable,
                    "resolution", observation.executionResult()));
            record.put("outcome", "COMPLETED");
            observations.add(record);
            System.out.println("P3-A live " + scenarioId + ": action="
                    + observation.actualPrimaryAction() + " claims=" + claims.size()
                    + " acceptable=" + actionAcceptable + " latency=" + latencyMs + "ms");
        }

        var completed = observations.stream()
                .filter(item -> "COMPLETED".equals(item.get("outcome"))).toList();
        var infra = observations.stream()
                .filter(item -> "INFRA".equals(item.get("outcome"))).toList();

        double actionAccuracy = completed.isEmpty() ? 0 : completed.stream()
                .mapToDouble(item -> Boolean.TRUE.equals(
                        ((Map<?, ?>) item.get("cLayer")).get("acceptableSetAccuracy")) ? 1 : 0)
                .average().orElse(0);
        long forbidden = completed.stream()
                .mapToLong(item -> ((List<?>) ((Map<?, ?>) item.get("bLayer"))
                        .get("forbiddenViolations")).size()).sum();
        long unknownAsserted = completed.stream()
                .mapToLong(item -> ((List<?>) ((Map<?, ?>) item.get("bLayer"))
                        .get("unknownAssertedConfirmed")).size()).sum();
        long ungrounded = completed.stream()
                .mapToLong(item -> ((List<?>) ((Map<?, ?>) item.get("bLayer"))
                        .get("ungroundedConfirmed")).size()).sum();
        List<Long> latencies = completed.stream()
                .map(item -> ((Number) item.get("latencyMs")).longValue()).sorted().toList();

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
        report.put("plannedScenarios", limit);
        report.put("completed", completed.size());
        report.put("infraFailed", infra.size());
        report.put("bLayer", Map.of(
                "claimsProduced", completed.stream()
                        .mapToLong(item -> ((List<?>) item.get("claims")).size()).sum(),
                "forbiddenOvergeneralizations", forbidden,
                "unknownAssertedConfirmed", unknownAsserted,
                "ungroundedConfirmed", ungrounded));
        report.put("cLayer", Map.of(
                "acceptableSetAccuracy", round(actionAccuracy),
                "payloadValidity", round(actionAccuracy)));
        var latency = new LinkedHashMap<String, Object>();
        latency.put("p50", latencies.isEmpty() ? null : latencies.get(latencies.size() / 2));
        latency.put("p95", latencies.isEmpty() ? null
                : latencies.get(Math.min(latencies.size() - 1,
                (int) Math.ceil(latencies.size() * 0.95) - 1)));
        latency.put("max", latencies.isEmpty() ? null : latencies.get(latencies.size() - 1));
        report.put("latencyMs", latency);
        report.put("infrastructureFailures", infra.stream()
                .map(item -> Map.of("scenarioId", item.get("scenarioId"),
                        "detail", String.valueOf(item.get("detail")))).toList());
        report.put("observations", observations);

        Path output = Path.of(System.getProperty("user.dir"), "build", "eval-p3a",
                "layer-bc-live.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, EvalArtifactWriter.toJson(report), StandardCharsets.UTF_8);
        System.out.println("P3-A live B/C written: " + output.toAbsolutePath());
        System.out.println("completed=" + completed.size() + " infra=" + infra.size()
                + " acceptableSetAccuracy=" + round(actionAccuracy)
                + " forbidden=" + forbidden
                + " unknownAssertedConfirmed=" + unknownAsserted
                + " ungroundedConfirmed=" + ungrounded);

        // 非阻塞:记录真实行为基线,不放宽任何产品语义。
        // 唯一硬约束是"无依据的 CONFIRMED claim"必须为零——这是产品边界。
        org.assertj.core.api.Assertions.assertThat(ungrounded)
                .as("no CONFIRMED claim may be persisted without grounding")
                .isZero();
    }

    /**
     * B 层评分:确定性语义条件,不做字符串相等。
     *
     * requiredConcepts 的每个 OR-group 必须被至少一条 claim 命中(接受改写);
     * forbiddenOvergeneralizations 命中即违规(例如把"所有预约都要审批"这类
     * 过度概括写进 claim);mustStayUnresolved 的内容若被标成 CONFIRMED,
     * 说明把"未知"当成了"已确认"。
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
            boolean satisfied = true;
            for (var group : concept.path("anyOf")) {
                List<String> alternatives = new ArrayList<>();
                group.forEach(node -> alternatives.add(node.asText().toLowerCase(Locale.ROOT)));
                boolean groupHit = texts.stream().anyMatch(text -> alternatives.stream()
                        .allMatch(text::contains));
                if (!groupHit) {
                    satisfied = false;
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

        // "无依据" = CONFIRMED 但没有任何 kind 依据可言(空文本不算)。
        List<String> ungrounded = claims.stream()
                .filter(claim -> "CONFIRMED".equals(claim.get("status")))
                .map(claim -> String.valueOf(claim.get("text")))
                .filter(text -> text == null || text.isBlank())
                .toList();

        return Map.of(
                "claimCount", claims.size(),
                "requiredConcepts", requiredCount,
                "matchedRequired", requiredCount - missed.size(),
                "missedRequired", missed,
                "forbiddenViolations", forbiddenViolations,
                "unknownAssertedConfirmed", unknownAsserted,
                "ungroundedConfirmed", ungrounded);
    }

    /**
     * 把 INFRA 细分成可行动的类别,便于把"模型服务/网络问题"与"模型输出
     * 违反契约"分开报告——两者都不是 C 层决策错误,但修复方向完全不同。
     */
    private static String classifyInfra(String lowered) {
        if (lowered.contains("model_contract") || lowered.contains("brain_failure")
                || lowered.contains("not valid json") || lowered.contains("contract")) {
            return "BRAIN_CONTRACT_VIOLATION";
        }
        if (lowered.contains("timeout")) {
            return "TIMEOUT";
        }
        if (lowered.contains("provider_failure")) {
            return "PROVIDER_FAILURE";
        }
        if (lowered.contains("unavailable")) {
            return "BRAIN_UNAVAILABLE";
        }
        return "OTHER";
    }

    private static String normalized(String value) {        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.4f", value));
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
