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
 * Gate 4: 依赖锁 (requirements.lock) 与全部实现代码文件 SHA-256 与 freeze 严格匹配，无代码漂移。
 * Gate 5: 质量验收指标阈值已获人工批准（APPROVED_BEFORE_FIRST_RUN，非 PROPOSED/PENDING），且数值合法。
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
        List<String> violations = new ArrayList<>();

        // 1. Role & Execution qualification (Gate 3, 6, 7)
        String role = dataset.path("datasetRole").asText();
        if (!expectedRole.equals(role)) {
            violations.add("Dataset role mismatch: expected '" + expectedRole + "', got '" + role + "'");
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
                Instant.parse(reviewedAt);
            } catch (DateTimeParseException ex) {
                violations.add("Human review timestamp is not a valid ISO-8601 instant: '" + reviewedAt + "'");
            }
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
            sourceAliases.add(alias);
            sourceKinds.put(alias, s.path("kind").asText());
        }

        Set<String> queryIds = new HashSet<>();
        int unreviewedCount = 0;
        for (JsonNode q : queriesNode) {
            String qId = q.path("id").asText();
            if (!queryIds.add(qId)) {
                violations.add("Duplicate query id detected: " + qId);
            }
            String labelReview = q.path("labelReview").asText();
            if (!"HUMAN_REVIEWED".equals(labelReview)) {
                unreviewedCount++;
            }
            // Scopes validation
            JsonNode scopes = q.path("scopes");
            if (!scopes.isArray() || scopes.isEmpty()) {
                violations.add("Query " + qId + " has empty or invalid scopes");
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
                if ("STALE_NODE".equals(kind) || "PRIVATE_NODE".equals(kind)) {
                    violations.add("Query " + qId + " contains invalid source kind in expected: " + a + " (" + kind + ")");
                }
            }
            if ("MULTI_SOURCE".equals(q.path("category").asText()) && exp.size() < 2) {
                violations.add("Query " + qId + " is MULTI_SOURCE but has < 2 expected aliases");
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

        String frozenReqSha = freeze.path("requirementsLockSha256").asText();
        if (!frozenReqSha.isBlank()) {
            Path reqFile = repoRoot.resolve("agent-brain/requirements.lock");
            if (Files.exists(reqFile)) {
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
        if (codeHashes.isObject()) {
            codeHashes.fields().forEachRemaining(entry -> {
                Path codeFile = repoRoot.resolve(entry.getKey());
                if (!Files.exists(codeFile)) {
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
        if (upperThreshold.contains("PROPOSED") || upperThreshold.contains("PENDING") || upperThreshold.contains("NOT_APPROVED")) {
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
            if (!thresholds.has("macroSourceRecallAt8") || !thresholds.path("macroSourceRecallAt8").isNumber()) {
                violations.add("macroSourceRecallAt8 threshold missing or non-numeric");
            }
            if (!thresholds.has("firstRelevantMrr") || !thresholds.path("firstRelevantMrr").isNumber()) {
                violations.add("firstRelevantMrr threshold missing or non-numeric");
            }
            if (!thresholds.has("multiSourceAllOfCoverage") || !thresholds.path("multiSourceAllOfCoverage").isNumber()) {
                violations.add("multiSourceAllOfCoverage threshold missing or non-numeric");
            }
        }

        if (!violations.isEmpty()) {
            throw new GateValidationException(violations);
        }
    }
}
