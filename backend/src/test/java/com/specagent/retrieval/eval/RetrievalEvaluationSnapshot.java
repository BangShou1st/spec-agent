package com.specagent.retrieval.eval;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.specagent.common.Hashes;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Read each input once. Only immutable strings cross the preflight/fixture boundary. */
public record RetrievalEvaluationSnapshot(String datasetFile, String datasetRaw, String freezeRaw, String auditRaw,
        boolean aiAudited) {
    public static final String AI_MODE = "AI_AUDITED_SYNTHETIC_EVALUATION";
    public static final String HUMAN_MODE = "HUMAN_APPROVED_INDEPENDENT_ACCEPTANCE";
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static JsonNode parse(String raw) {
        try {
            JsonNode value = JSON.readTree(raw);
            if (value == null || !value.isObject()) throw new IllegalArgumentException("Expected JSON object");
            return value;
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    public JsonNode dataset() { return parse(datasetRaw); }
    public JsonNode freeze() { return parse(freezeRaw); }
    public String datasetSha256() { return Hashes.sha256Hex(datasetRaw); }
    public String freezeSha256() { return Hashes.sha256Hex(freezeRaw); }
    // A revision, a new filename or a changed threshold never renews holdout eligibility.
    public String familyId() { return datasetFile.equals("acceptance-candidate.v1.json") ? "rag-d3-v1-68" : "rag-d3-v2-68"; }

    public static RetrievalEvaluationSnapshot load(Path root, String file, String mode) {
        if (!Set.of("acceptance-candidate.v1.json", "acceptance-candidate.v2.json", "acceptance-candidate.v2-revised.json",
                "acceptance-candidate.v2.1.json", "acceptance-candidate.v2.2.json", "acceptance-candidate.v2.3.json").contains(file))
            throw new IllegalArgumentException("Unknown candidate: " + file);
        if (!Set.of(HUMAN_MODE, AI_MODE).contains(mode)) throw new IllegalArgumentException("Unknown evaluation mode");
        boolean ai = AI_MODE.equals(mode);
        if (ai != file.equals("acceptance-candidate.v2.3.json")) throw new IllegalArgumentException("Candidate/mode mismatch");
        Path data = root.resolve("contracts/retrieval/evaluation");
        try {
            String raw = Files.readString(data.resolve(file));
            String frozen = Files.readString(data.resolve(file.replace("candidate", "freeze")));
            String audit = ai ? Files.readString(data.resolve("ai-review-decisions.v1.json")) : "{}";
            var snapshot = new RetrievalEvaluationSnapshot(file, raw, frozen, audit, ai);
            snapshot.validate(root);
            return snapshot;
        } catch (IOException ex) { throw new UncheckedIOException("Candidate/freeze/audit missing or unreadable", ex); }
    }

    public void validate(Path root) {
        validate(root, Map.of());
    }

    // Only RecoveryGate calls this after validating the immutable, byte-exact repair allowlist.
    void validate(Path root, Map<String,String> authorizedRepairs) {
        JsonNode candidate = dataset(), frozen = freeze();
        if(!authorizedRepairs.isEmpty()) {
            var code=(com.fasterxml.jackson.databind.node.ObjectNode)frozen.path("codeHashes");
            authorizedRepairs.forEach((file,hash)->{ if(code.has(file)) code.put(file,hash); });
        }
        if (!datasetFile.equals(frozen.path("datasetFile").asText())) throw new IllegalArgumentException("Frozen dataset filename mismatch");
        if (aiAudited) {
            RetrievalAcceptanceGateValidator.validateAiBaseline(candidate, datasetRaw, frozen, root);
            JsonNode audit = parse(auditRaw);
            require(familyId().equals(frozen.path("evaluationFamilyId").asText()), "Evaluation family binding");
            require(frozen.path("dimensions").isIntegralNumber() && frozen.path("dimensions").asLong() == 1024
                    && "ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d".equals(frozen.path("modelDigest").asText()), "Fixed model dimension/digest binding");
            require(fileSha(root.resolve("contracts/retrieval/evaluation/runtime-inventory.v1.json"))
                    .equals(frozen.path("runtimeInventorySha256").asText()), "Runtime inventory hash");
            JsonNode parentFreeze = parse(read(root.resolve("contracts/retrieval/evaluation/acceptance-freeze.v2.2.json")));
            require(fileSha(root.resolve("contracts/retrieval/evaluation/acceptance-freeze.v2.2.json"))
                    .equals(frozen.path("parentFreezeSha256").asText()), "Parent freeze hash");
            Set<String> requiredCode = new HashSet<>(); parentFreeze.path("codeHashes").fieldNames().forEachRemaining(requiredCode::add);
            requiredCode.addAll(List.of("backend/src/test/java/com/specagent/retrieval/eval/RetrievalEvaluationSnapshot.java",
                    "agent-brain/scripts/retrieval_acceptance_contract.py"));
            Set<String> frozenCode = new HashSet<>(); frozen.path("codeHashes").fieldNames().forEachRemaining(frozenCode::add);
            require(frozenCode.containsAll(requiredCode), "Incomplete implementation freeze");
            require("ai-review-decisions.v1".equals(audit.path("schemaVersion").asText()), "Audit version");
            require(AI_MODE.equals(audit.path("evaluationMode").asText()), "Audit mode");
            require("AI_AUDITED".equals(audit.path("reviewStatus").asText()), "Audit status");
            require(audit.path("humanApproval").isBoolean() && !audit.path("humanApproval").asBoolean(), "AI audit must not claim human approval");
            require(datasetSha256().equals(audit.path("datasetSha256").asText()), "Audit dataset binding");
            require(Hashes.sha256Hex(auditRaw).equals(frozen.path("aiReviewSha256").asText()), "Audit hash");
            require(audit.path("metricContract").equals(frozen.path("metricContract")), "Metric contract binding");
            require("NEVER_USED".equals(audit.path("tuningUse").asText()) && audit.path("executedQueries").isIntegralNumber()
                    && audit.path("executedQueries").canConvertToInt()
                    && audit.path("executedQueries").asInt() == 0, "Audit execution/tuning history");
            validateDecisions(audit.path("queryDecisions"), candidate.path("queries"), "queryId", "id", root, datasetSha256(),authorizedRepairs,freeze());
            validateDecisions(audit.path("sourceDecisions"), candidate.path("sources"), "sourceAlias", "alias", root, datasetSha256(),authorizedRepairs,freeze());
            require(candidate.path("parentDatasetSha256").asText().equals(audit.path("parentDatasetSha256").asText()), "Parent binding");
            Path parent = root.resolve("contracts/retrieval/evaluation/acceptance-candidate.v2.2.json");
            require(candidate.path("parentDatasetSha256").asText().equals(fileSha(parent)), "Parent hash");
            require(frozen.path("proposedThresholds").equals(audit.path("metricContract").path("hardThresholds")), "Threshold contract binding");
            require(audit.path("metricContract").path("noAnswerPolicy").asText().equals(frozen.path("noAnswerPolicy").asText()), "No-answer contract binding");
        } else {
            RetrievalAcceptanceGateValidator.validateForExecution(candidate, datasetRaw, frozen, root,
                    "INDEPENDENT_ACCEPTANCE_CANDIDATE", 68, 28);
        }
    }

    private static void validateDecisions(JsonNode decisions, JsonNode entries, String key, String entryKey, Path root, String datasetSha,
            Map<String,String> authorizedRepairs,JsonNode originalFreeze) {
        require(decisions.isArray() && decisions.size() == entries.size(), "Complete audit required");
        Map<String, JsonNode> indexed = new HashMap<>();
        entries.forEach(entry -> indexed.put(entry.path(entryKey).asText(), entry));
        Set<String> seen = new HashSet<>();
        for (JsonNode decision : decisions) {
            String id = decision.path(key).asText();
            require(seen.add(id) && indexed.containsKey(id), "Unknown/duplicate audit entry");
            require(Set.of("AI_ACCEPTED", "AI_CORRECTED").contains(decision.path("conclusion").asText()), "Unresolved/unreviewed audit entry");
            require("AI".equals(decision.path("reviewerType").asText())
                    && "GPT-6.1 Sol".equals(decision.path("reviewModel").asText()), "AI reviewer identity");
            require(decision.path("after").equals(indexed.get(id)), "Audit current labels/source mismatch");
            require(datasetSha.equals(decision.path("datasetSha256").asText()), "Decision dataset binding");
            require(decision.path("before").isObject() && decision.path("actuallyModified").isBoolean()
                    && decision.path("actuallyModified").asBoolean() == !decision.path("before").equals(decision.path("after")), "Decision diff consistency");
            JsonNode evidence = decision.path("evidence");
            require(evidence.isArray() && !evidence.isEmpty(), "Audit evidence missing");
            for (JsonNode ref : evidence) {
                Path path = root.resolve(ref.path("file").asText()).normalize();
                require(path.toAbsolutePath().startsWith(root.toAbsolutePath().normalize()) && Files.isRegularFile(path), "Invalid evidence file");
                require(ref.path("line").isIntegralNumber() && ref.path("line").asInt() > 0, "Invalid evidence line");
                require(ref.path("line").asInt() <= read(path).lines().count(), "Evidence line outside file");
                String file=ref.path("file").asText(),expected=ref.path("sha256").asText();
                if(authorizedRepairs.containsKey(file)) {
                    require(expected.equals(originalFreeze.path("codeHashes").path(file).asText()),"Repair evidence parent binding");
                    expected=authorizedRepairs.get(file);
                }
                require(fileSha(path).equals(expected), "Evidence drift");
            }
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    static String fileSha(Path path) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public void requireRunAuthorization(String authorization) {
        require((datasetSha256() + ":" + freezeSha256()).equals(authorization), "Explicit first-run authorization for this snapshot required");
    }

    public void requireRuntime(Path root, String executable) {
        require(executable != null && !executable.isBlank(), "Explicit frozen Python interpreter required");
        Path inventory = root.resolve("contracts/retrieval/evaluation/runtime-inventory.v1.json");
        require(fileSha(inventory).equals(freeze().path("runtimeInventorySha256").asText()), "Runtime inventory drift");
        try {
            var process = new ProcessBuilder(executable, root.resolve("agent-brain/scripts/retrieval_acceptance_contract.py").toString(),
                    "--runtime-inventory").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IllegalStateException("Runtime inventory timed out");
            }
            String actual = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            require(process.exitValue() == 0 && parse(actual).equals(parse(read(inventory))), "Actual runtime differs from frozen interpreter/dependencies");
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
