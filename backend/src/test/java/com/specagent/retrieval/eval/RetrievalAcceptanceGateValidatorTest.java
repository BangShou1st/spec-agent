package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specagent.common.Hashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalAcceptanceGateValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record SyntheticFixture(ObjectNode dataset, String rawJson, ObjectNode freeze) {}

    private SyntheticFixture createValidSyntheticFixture() {
        ObjectNode dataset = MAPPER.createObjectNode();
        dataset.put("datasetRole", "INDEPENDENT_ACCEPTANCE_CANDIDATE");
        dataset.put("tuningUse", "NEVER_USED");
        dataset.put("executionStatus", "NOT_RUN");
        dataset.put("humanReviewStatus", "APPROVED");
        dataset.put("reviewedBy", "Lead Auditor Zhang");
        dataset.put("reviewedAt", Instant.now().toString());

        ArrayNode sources = dataset.putArray("sources");
        ObjectNode s1 = sources.addObject();
        s1.put("alias", "N01");
        s1.put("kind", "NODE");
        s1.put("text", "Valid node 1");
        ObjectNode s2 = sources.addObject();
        s2.put("alias", "N02");
        s2.put("kind", "NODE");
        s2.put("text", "Valid node 2");

        ArrayNode queries = dataset.putArray("queries");
        ObjectNode q1 = queries.addObject();
        q1.put("id", "Q001");
        q1.put("category", "SINGLE_SOURCE_NODE");
        q1.put("labelReview", "HUMAN_REVIEWED");
        q1.putArray("scopes").add("PROJECT");
        q1.putArray("expectedAliases").add("N01");
        q1.putArray("forbiddenAliases");

        ObjectNode q2 = queries.addObject();
        q2.put("id", "Q002");
        q2.put("category", "MULTI_SOURCE");
        q2.put("labelReview", "HUMAN_REVIEWED");
        q2.putArray("scopes").add("PROJECT");
        q2.putArray("expectedAliases").add("N01").add("N02");
        q2.putArray("forbiddenAliases");

        String rawJson = dataset.toString();
        String sha = Hashes.sha256Hex(rawJson);

        ObjectNode freeze = MAPPER.createObjectNode();
        freeze.put("datasetSha256", sha);
        freeze.put("requirementsLockSha256", "");
        freeze.putObject("codeHashes");
        freeze.put("thresholdStatus", "APPROVED_BEFORE_FIRST_RUN");
        ObjectNode thresholds = freeze.putObject("proposedThresholds");
        thresholds.put("macroSourceRecallAt8", 0.85);
        thresholds.put("firstRelevantMrr", 0.75);
        thresholds.put("multiSourceAllOfCoverage", 0.75);

        return new SyntheticFixture(dataset, rawJson, freeze);
    }

    @Test
    @DisplayName("完整满足 7 点门禁的合成数据能够顺利通过验证")
    void allGatesPassingAllowsExecution() {
        SyntheticFixture f = createValidSyntheticFixture();
        RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), f.rawJson(), f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        );
    }

    @Test
    @DisplayName("顶级状态为 PENDING 时必须被拒绝")
    void pendingHumanReviewStatusIsRejected() {
        SyntheticFixture f = createValidSyntheticFixture();
        f.dataset().put("humanReviewStatus", "PENDING");
        String raw = f.dataset().toString();
        f.freeze().put("datasetSha256", Hashes.sha256Hex(raw));

        assertThatThrownBy(() -> RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), raw, f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        )).isInstanceOf(RetrievalAcceptanceGateValidator.GateValidationException.class)
          .hasMessageContaining("humanReviewStatus is not APPROVED");
    }

    @Test
    @DisplayName("审阅者为 AI 或占位符时必须被拒绝")
    void missingOrPlaceholderReviewerIsRejected() {
        SyntheticFixture f = createValidSyntheticFixture();
        f.dataset().put("reviewedBy", "ai-agent");
        String raw = f.dataset().toString();
        f.freeze().put("datasetSha256", Hashes.sha256Hex(raw));

        assertThatThrownBy(() -> RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), raw, f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        )).isInstanceOf(RetrievalAcceptanceGateValidator.GateValidationException.class)
          .hasMessageContaining("Genuine human reviewer identity missing");
    }

    @Test
    @DisplayName("审阅时间戳非合法 ISO-8601 时必须被拒绝")
    void invalidIsoTimestampIsRejected() {
        SyntheticFixture f = createValidSyntheticFixture();
        f.dataset().put("reviewedAt", "2026-10-22 14:00:00"); // 非 ISO-8601
        String raw = f.dataset().toString();
        f.freeze().put("datasetSha256", Hashes.sha256Hex(raw));

        assertThatThrownBy(() -> RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), raw, f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        )).isInstanceOf(RetrievalAcceptanceGateValidator.GateValidationException.class)
          .hasMessageContaining("not a valid ISO-8601 instant");
    }

    @Test
    @DisplayName("存在未审阅 query (labelReview != HUMAN_REVIEWED) 时必须被拒绝")
    void unreviewedQueriesCountIsRejected() {
        SyntheticFixture f = createValidSyntheticFixture();
        ((ObjectNode) f.dataset().path("queries").get(0)).put("labelReview", "PENDING_HUMAN_REVIEW");
        String raw = f.dataset().toString();
        f.freeze().put("datasetSha256", Hashes.sha256Hex(raw));

        assertThatThrownBy(() -> RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), raw, f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        )).isInstanceOf(RetrievalAcceptanceGateValidator.GateValidationException.class)
          .hasMessageContaining("query label(s) are not HUMAN_REVIEWED");
    }

    @Test
    @DisplayName("阈值处于 PROPOSED_BASELINE 状态时必须被拒绝")
    void unapprovedThresholdStatusIsRejected() {
        SyntheticFixture f = createValidSyntheticFixture();
        f.freeze().put("thresholdStatus", "PROPOSED_BASELINE; awaiting review");

        assertThatThrownBy(() -> RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), f.rawJson(), f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        )).isInstanceOf(RetrievalAcceptanceGateValidator.GateValidationException.class)
          .hasMessageContaining("Acceptance thresholds unapproved");
    }

    @Test
    @DisplayName("数据集角色不匹配 (如 DEVELOPMENT_CALIBRATION) 必须被拒绝")
    void datasetRoleMismatchIsRejected() {
        SyntheticFixture f = createValidSyntheticFixture();
        f.dataset().put("datasetRole", "DEVELOPMENT_CALIBRATION");
        String raw = f.dataset().toString();
        f.freeze().put("datasetSha256", Hashes.sha256Hex(raw));

        assertThatThrownBy(() -> RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), raw, f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        )).isInstanceOf(RetrievalAcceptanceGateValidator.GateValidationException.class)
          .hasMessageContaining("Dataset role mismatch");
    }

    @Test
    @DisplayName("有调参历史记录 (tuningUse != NEVER_USED) 必须被拒绝")
    void tuningContaminationIsRejected() {
        SyntheticFixture f = createValidSyntheticFixture();
        f.dataset().put("tuningUse", "TUNED_ON_CALIBRATION");
        String raw = f.dataset().toString();
        f.freeze().put("datasetSha256", Hashes.sha256Hex(raw));

        assertThatThrownBy(() -> RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), raw, f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        )).isInstanceOf(RetrievalAcceptanceGateValidator.GateValidationException.class)
          .hasMessageContaining("Contaminated by tuning use");
    }

    @Test
    @DisplayName("有执行历史记录 (executionStatus != NOT_RUN) 必须被拒绝")
    void priorExecutionHistoryIsRejected() {
        SyntheticFixture f = createValidSyntheticFixture();
        f.dataset().put("executionStatus", "EXECUTED_RUN_1");
        String raw = f.dataset().toString();
        f.freeze().put("datasetSha256", Hashes.sha256Hex(raw));

        assertThatThrownBy(() -> RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), raw, f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        )).isInstanceOf(RetrievalAcceptanceGateValidator.GateValidationException.class)
          .hasMessageContaining("Prior execution history detected");
    }

    @Test
    @DisplayName("数据原始 JSON 哈希与 freeze 不匹配时必须被拒绝")
    void datasetShaMismatchIsRejected() {
        SyntheticFixture f = createValidSyntheticFixture();
        f.freeze().put("datasetSha256", "0000000000000000000000000000000000000000000000000000000000000000");

        assertThatThrownBy(() -> RetrievalAcceptanceGateValidator.validateForExecution(
                f.dataset(), f.rawJson(), f.freeze(), Path.of("."),
                "INDEPENDENT_ACCEPTANCE_CANDIDATE", 2, 2
        )).isInstanceOf(RetrievalAcceptanceGateValidator.GateValidationException.class)
          .hasMessageContaining("Dataset SHA-256 mismatch");
    }
}
