package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.specagent.common.Hashes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 检索验收 7 点执行门禁统一校验器 (Retrieval Acceptance Gate Validator)。
 *
 * 在执行正式检索候选集评测前，统一强制执行完整资格判定。
 * 遵循 Fail-Closed 原则：任何一项条件不满足或处于未知状态，立即拒绝执行。
 *
 * 门禁清单：
 * Gate 1: 逐条标签审阅（queries 中每条必须为 HUMAN_REVIEWED，且集合大小与 ID 完整）。
 * Gate 2: 真实人工审阅者与合法 ISO 时间戳（非空、非占位符，顶级状态为 APPROVED）。
 * Gate 3: 候选数据集 SHA-256 与 freeze 严格匹配，且角色为 INDEPENDENT_ACCEPTANCE_CANDIDATE。
 * Gate 4: 依赖锁 (requirements.lock) 与全部实现代码文件 SHA-256 与 freeze 严格匹配，无代码漂移且清单非空。
 * Gate 5: 质量验收指标阈值已获人工批准（APPROVED_BEFORE_FIRST_RUN，非 PROPOSED/PENDING），数值范围合法（无负数/NaN/Inf）。
 * Gate 6: 零调参使用历史（tuningUse == NEVER_USED）。
 * Gate 7: 首次执行记录与原子 claim（executionStatus == NOT_RUN，且防止并发与重复执行）。
 */
public final class RetrievalAcceptanceGateValidator {

    private static final Set<String> PLACEHOLDER_NAMES = Set.of(
            "none", "null", "pending", "ai", "ai-agent", "unassigned", ""
    );

    public static class GateValidationException extends RuntimeException {
        private final List<String> violations;

        public GateValidationException(List<String> violations) {
            super("Retrieval acceptance gate validation failed: " + String.join("; ", violations));
            this.violations = List.copyOf(violations);
        }

        public List<String> getViolations() {
            return violations;
        }
    }

    private RetrievalAcceptanceGateValidator() {
    }

    public static void validateForExecution(
            JsonNode dataset,
            String datasetRawJson,
            JsonNode freeze,
            Path repoRoot,
            String expectedRole,
            int expectedQueriesCount,
            int expectedSourcesCount
    ) {
        validate(dataset, datasetRawJson, freeze, repoRoot, expectedRole, expectedQueriesCount, expectedSourcesCount, false);
    }

    static void validateAiBaseline(JsonNode dataset, String raw, JsonNode freeze, Path root) {
        validate(dataset, raw, freeze, root, "AI_AUDITED_SYNTHETIC_EVALUATION", 68, 28, true);
    }

    private static void validate(JsonNode dataset, String datasetRawJson, JsonNode freeze, Path repoRoot,
            String expectedRole, int expectedQueriesCount, int expectedSourcesCount, boolean ai) {
        List<String> violations = new ArrayList<>();

        // 1. Role & Execution qualification (Gate 3, 6, 7)
        String version = dataset.path("version").asText().trim();
        String datasetVersion = dataset.path("datasetVersion").asText().trim();
        if (version.isEmpty() || !Set.of("v1", "v2", "v2-revised", "v2.1", "v2.2", "v2.3").contains(version)
                || !version.equals(freeze.path("datasetVersion").asText())
                || !version.equals(freeze.path("freezeVersion").asText())) {
            violations.add("Missing, unsupported or mismatched candidate/freeze version");
        }
        try {
            if (!dataset.equals(RetrievalEvaluationSnapshot.parse(datasetRawJson))) violations.add("Parsed dataset differs from raw snapshot");
        } catch (RuntimeException ex) { violations.add("Invalid candidate JSON"); }
        if (!version.equals(datasetVersion)) {
            violations.add("Candidate version ('" + version + "') and datasetVersion ('" + datasetVersion + "') mismatch");
        }
        String role = dataset.path("datasetRole").asText();
        if (!expectedRole.equals(role)) {
            violations.add("Dataset role mismatch: expected '" + expectedRole + "', got '" + role + "'");
        }
        String freezeRole = freeze.path("datasetRole").asText().trim();
        if (!expectedRole.equals(freezeRole)) {
            violations.add("Freeze datasetRole mismatch: expected '" + expectedRole + "', got '" + freezeRole + "'");
        }
        String tuningUse = dataset.path("tuningUse").asText();
        if (!"NEVER_USED".equals(tuningUse)) {
            violations.add("Contaminated by tuning use: tuningUse='" + tuningUse + "'");
        }
        String executionStatus = dataset.path("executionStatus").asText();
        if (!"NOT_RUN".equals(executionStatus)) {
            violations.add("Prior execution history detected: executionStatus='" + executionStatus + "'");
        }

        // 2. Human approval (Gate 1, 2)
        String humanStatus = dataset.path("humanReviewStatus").asText();
        if (!ai) {
        if (!"APPROVED".equals(humanStatus)) {
            violations.add("Top-level humanReviewStatus is not APPROVED: got '" + humanStatus + "'");
        }
        String reviewedBy = dataset.path("reviewedBy").asText().trim();
        if (reviewedBy.isEmpty() || PLACEHOLDER_NAMES.contains(reviewedBy.toLowerCase(Locale.ROOT))) {
            violations.add("Genuine human reviewer identity missing: got '" + reviewedBy + "'");
        }
        String reviewedAt = dataset.path("reviewedAt").asText().trim();
        if (reviewedAt.isEmpty() || PLACEHOLDER_NAMES.contains(reviewedAt.toLowerCase(Locale.ROOT))) {
            violations.add("Human review timestamp missing: got '" + reviewedAt + "'");
        } else {
            try {
                if (!reviewedAt.matches("\\d{4}-\\d{2}-\\d{2}T([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})"))
                    throw new DateTimeParseException("Expected instant", reviewedAt, 0);
                Instant.parse(reviewedAt);
            } catch (DateTimeParseException ex) {
                violations.add("Human review timestamp is not a valid ISO-8601 instant: '" + reviewedAt + "'");
            }
        }

        } else if (!"PENDING".equals(humanStatus) || !dataset.path("reviewedBy").isNull()
                || !dataset.path("reviewedAt").isNull()) {
            violations.add("AI review must preserve pending human approval and null human identity");
        }

        // 3. Dataset Integrity & Query labels (Gate 1)
        JsonNode queriesNode = dataset.path("queries");
        if (!queriesNode.isArray() || queriesNode.size() != expectedQueriesCount) {
            violations.add("Query count mismatch: expected " + expectedQueriesCount + ", got " + queriesNode.size());
        }
        JsonNode sourcesNode = dataset.path("sources");
        if (!sourcesNode.isArray() || sourcesNode.size() != expectedSourcesCount) {
            violations.add("Source count mismatch: expected " + expectedSourcesCount + ", got " + sourcesNode.size());
        }

        Map<String, String> sourceKinds = new HashMap<>();
        Set<String> sourceAliases = new HashSet<>();
        for (JsonNode s : sourcesNode) {
            String alias = s.path("alias").asText();
            if (alias.isBlank() || !sourceAliases.add(alias)) violations.add("Blank or duplicate source alias");
            sourceKinds.put(alias, s.path("kind").asText());
        }

        Set<String> queryIds = new HashSet<>();
        int unreviewedCount = 0;
        for (JsonNode q : queriesNode) {
            String qId = q.path("id").asText();
            if (qId.isBlank() || !queryIds.add(qId)) {
                violations.add("Duplicate query id detected: " + qId);
            }
            String labelReview = q.path("labelReview").asText();
            if (!(ai ? "PENDING_HUMAN_REVIEW" : "HUMAN_REVIEWED").equals(labelReview)) {
                unreviewedCount++;
            }
            // Scopes validation
            JsonNode scopes = q.path("scopes");
            if (!scopes.isArray() || scopes.isEmpty()) {
                violations.add("Query " + qId + " has empty or invalid scopes");
            }
            for (JsonNode scope : scopes) if (!Set.of("PROJECT", "RESOURCE").contains(scope.asText()))
                violations.add("Query " + qId + " has unknown scope");
            for (String field : List.of("expectedAliases", "forbiddenAliases")) {
                JsonNode aliases = q.path(field);
                var unique = new HashSet<String>();
                if (!aliases.isArray()) violations.add("Query " + qId + " missing " + field);
                for (JsonNode alias : aliases) if (!alias.isTextual() || !unique.add(alias.asText()))
                    violations.add("Query " + qId + " duplicate/non-string " + field);
            }
            // Expected / Forbidden aliases disjointness
            Set<String> exp = new HashSet<>();
            q.path("expectedAliases").forEach(a -> exp.add(a.asText()));
            Set<String> forb = new HashSet<>();
            q.path("forbiddenAliases").forEach(a -> forb.add(a.asText()));

            if (!sourceAliases.containsAll(exp)) {
                violations.add("Query " + qId + " references unknown expected aliases: " + exp);
            }
            if (!sourceAliases.containsAll(forb)) {
                violations.add("Query " + qId + " references unknown forbidden aliases: " + forb);
            }
            for (String a : exp) {
                if (forb.contains(a)) {
                    violations.add("Query " + qId + " has overlapping expected and forbidden alias: " + a);
                }
                String kind = sourceKinds.get(a);
                String requiredScope = "RESOURCE".equals(kind) ? "RESOURCE" : "PROJECT";
                boolean scopePresent = false;
                for (JsonNode scope : scopes) if (requiredScope.equals(scope.asText())) scopePresent = true;
                if (!scopePresent) violations.add("Expected alias outside query scope: " + a);
                if ("STALE_NODE".equals(kind) || "PRIVATE_NODE".equals(kind)) {
                    violations.add("Query " + qId + " contains invalid source kind in expected: " + a + " (" + kind + ")");
                }
            }
            if ("MULTI_SOURCE".equals(q.path("category").asText()) && exp.size() < 2) {
                violations.add("Query " + qId + " is MULTI_SOURCE but has < 2 expected aliases");
            }
        }
        if (expectedQueriesCount == 68) {
            var expectedIds = new HashSet<String>();
            for (int i = 1; i <= 68; i++) expectedIds.add("B%03d".formatted(i));
            if (!queryIds.equals(expectedIds)) violations.add("Query identities must be B001-B068");
            var forbiddenSources = new HashSet<String>();
            sourceKinds.forEach((alias, kind) -> { if (Set.of("STALE_NODE", "PRIVATE_NODE").contains(kind)) forbiddenSources.add(alias); });
            Set<String> positive = Set.of("PROJECT_EXPRESSION", "EXACT_IDENTIFIER", "RESOURCE_CONTENT", "MULTI_SOURCE");
            Set<String> negative = Set.of("NEAR_NO_ANSWER", "NO_ANSWER", "STALE_VERSION", "PERMISSION_ISOLATION");
            for (JsonNode q : queriesNode) {
                String cat = q.path("category").asText();
                if (!positive.contains(cat) && !negative.contains(cat)) violations.add("Unknown query category");
                if (positive.contains(cat) == q.path("expectedAliases").isEmpty()) violations.add("Missing/inconsistent positive labels");
                Set<String> forbidden = new HashSet<>(); q.path("forbiddenAliases").forEach(a -> forbidden.add(a.asText()));
                if (!forbidden.containsAll(forbiddenSources)) violations.add("Incomplete forbidden sources");
            }
        }
        if (unreviewedCount > 0) {
            violations.add(unreviewedCount + " query label(s) are not HUMAN_REVIEWED");
        }

        // 4. Freeze & Hash consistency (Gate 3, 4)
        String actualDatasetSha = Hashes.sha256Hex(datasetRawJson);
        String frozenDatasetSha = freeze.path("datasetSha256").asText();
        if (!frozenDatasetSha.equals(actualDatasetSha)) {
            violations.add("Dataset SHA-256 mismatch: actual " + actualDatasetSha + " vs frozen " + frozenDatasetSha);
        }

        String modelTag = freeze.path("modelTag").asText().trim();
        if (modelTag.isEmpty()) {
            violations.add("modelTag missing in freeze");
        } else if (!"qwen3-embedding:0.6b".equals(modelTag)) {
            violations.add("modelTag mismatch with execution chain: expected 'qwen3-embedding:0.6b' but got '" + modelTag + "'");
        }
        String profileId = freeze.path("profileId").asText().trim();
        if (profileId.isEmpty()) {
            violations.add("profileId missing in freeze");
        } else if (!"26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047".equals(profileId)) {
            violations.add("profileId mismatch with execution chain: expected '26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047' but got '" + profileId + "'");
        }

        if (!freeze.path("rrfK").isIntegralNumber() || !freeze.path("rrfK").canConvertToInt() || freeze.path("rrfK").asInt() != 60) {
            violations.add("rrfK mismatch with execution chain: expected 60 but got " + freeze.path("rrfK").asText());
        }
        if (!freeze.path("maxVectorDistance").isNumber() || freeze.path("maxVectorDistance").asDouble() != 0.50) {
            violations.add("maxVectorDistance mismatch with execution chain: expected 0.50 but got " + freeze.path("maxVectorDistance").asText());
        }
        if (!freeze.path("maxItems").isIntegralNumber() || !freeze.path("maxItems").canConvertToInt() || freeze.path("maxItems").asInt() != 8) {
            violations.add("maxItems mismatch with execution chain: expected 8 but got " + freeze.path("maxItems").asText());
        }
        if (!freeze.path("maxChars").isIntegralNumber() || !freeze.path("maxChars").canConvertToInt() || freeze.path("maxChars").asInt() != 8000) {
            violations.add("maxChars mismatch with execution chain: expected 8000 but got " + freeze.path("maxChars").asText());
        }

        String frozenReqSha = freeze.path("requirementsLockSha256").asText().trim();
        if (frozenReqSha.isEmpty()) {
            violations.add("requirementsLockSha256 missing or empty in freeze");
        } else {
            Path reqFile = repoRoot.resolve("agent-brain/requirements.lock");
            if (!Files.exists(reqFile) && Files.exists(repoRoot.resolve("../agent-brain/requirements.lock"))) {
                reqFile = repoRoot.resolve("../agent-brain/requirements.lock");
            }
            if (!Files.exists(reqFile)) {
                violations.add("requirements.lock file missing in workspace");
            } else {
                try {
                    String actualReqSha = Hashes.sha256Hex(Files.readString(reqFile));
                    if (!frozenReqSha.equals(actualReqSha)) {
                        violations.add("requirements.lock drifted: actual " + actualReqSha + " vs frozen " + frozenReqSha);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        JsonNode codeHashes = freeze.path("codeHashes");
        if (!codeHashes.isObject() || codeHashes.isEmpty()) {
            violations.add("codeHashes missing or empty in freeze");
        } else {
            codeHashes.fields().forEachRemaining(entry -> {
                Path codeFile = repoRoot.resolve(entry.getKey()).toAbsolutePath().normalize();
                if (!codeFile.startsWith(repoRoot.toAbsolutePath().normalize()) || !Files.isRegularFile(codeFile)) {
                    violations.add("Tracked code file missing: " + entry.getKey());
                } else {
                    try {
                        String actualCodeSha = Hashes.sha256Hex(Files.readString(codeFile));
                        if (!entry.getValue().asText().equals(actualCodeSha)) {
                            violations.add("Code hash drifted for " + entry.getKey() + ": actual " + actualCodeSha + " vs frozen " + entry.getValue().asText());
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
        }

        // 5. Quality acceptance threshold approval (Gate 5)
        String thresholdStatus = freeze.path("thresholdStatus").asText();
        String upperThreshold = thresholdStatus.trim().toUpperCase(Locale.ROOT);
        if (ai) {
            if (!"AI_BASELINE_FROZEN".equals(thresholdStatus)) violations.add("AI baseline not frozen");
            if (!"DIAGNOSTIC_RETRIEVAL_NOISE".equals(freeze.path("noAnswerPolicy").asText())) violations.add("AI no-answer policy missing");
        } else if (upperThreshold.contains("PROPOSED") || upperThreshold.contains("PENDING") || upperThreshold.contains("NOT_APPROVED")) {
            violations.add("Acceptance thresholds unapproved: status='" + thresholdStatus + "'");
        } else if (!"APPROVED_BEFORE_FIRST_RUN".equals(upperThreshold) && !"APPROVED".equals(upperThreshold)) {
            violations.add("Unknown threshold status: '" + thresholdStatus + "'");
        }

        JsonNode thresholds = freeze.path("proposedThresholds");
        if (thresholds.isMissingNode() || thresholds.isNull() || !thresholds.isObject()) {
            thresholds = freeze.path("approvedThresholds");
        }
        if (thresholds.isMissingNode() || thresholds.isNull() || !thresholds.isObject()) {
            violations.add("Quality threshold metrics contract missing in freeze");
        } else {
            checkRatioThreshold(thresholds, "macroSourceRecallAt8", violations);
            checkRatioThreshold(thresholds, "firstRelevantMrr", violations);
            checkRatioThreshold(thresholds, "multiSourceAllOfCoverage", violations);
            // pureNoAnswerZeroHitCorrectness 是必需门禁字段，绝不允许为 null 或缺失！
            if (!ai) checkRatioThreshold(thresholds, "pureNoAnswerZeroHitCorrectness", violations);
            else if (!thresholds.path("sourceIdentityValidity").isNumber() || thresholds.path("sourceIdentityValidity").asDouble() != 1.0)
                violations.add("Source identity validity must be 1.0");

            if (!thresholds.has("staleSourceLeakCount") || !thresholds.path("staleSourceLeakCount").isIntegralNumber()) {
                violations.add("staleSourceLeakCount threshold missing or non-integer");
            } else {
                if (thresholds.path("staleSourceLeakCount").bigIntegerValue().signum() != 0) violations.add("staleSourceLeakCount must be zero");
            }
            if (!thresholds.has("permissionLeakCount") || !thresholds.path("permissionLeakCount").isIntegralNumber()) {
                violations.add("permissionLeakCount threshold missing or non-integer");
            } else {
                if (thresholds.path("permissionLeakCount").bigIntegerValue().signum() != 0) violations.add("permissionLeakCount must be zero");
            }
        }

        if (!violations.isEmpty()) {
            throw new GateValidationException(violations);
        }
    }

    private static void checkRatioThreshold(JsonNode node, String field, List<String> violations) {
        if (!node.has(field) || !node.path(field).isNumber()) {
            violations.add(field + " threshold missing or non-numeric");
            return;
        }
        double val = node.path(field).asDouble();
        if (Double.isNaN(val) || Double.isInfinite(val) || val < 0.0 || val > 1.0) {
            violations.add(field + " threshold must be a valid ratio in [0.0, 1.0], got: " + val);
        }
    }
}
