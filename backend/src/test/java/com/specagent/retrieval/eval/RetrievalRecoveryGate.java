package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.specagent.common.Hashes;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static com.specagent.retrieval.eval.RetrievalExecutionClaimManager.*;
import static com.specagent.retrieval.eval.RetrievalEvaluationSnapshot.*;

/** Recovery is a separately authorized measurement, never a renewed first-run qualification. */
public final class RetrievalRecoveryGate {
    public static final String PARENT="2a419207-4cf2-4e3d-9361-0eb4219a6199";
    public static final String CANDIDATE="eb1262001f1f67a28c6210a6cd7846b8b7adf547de3453096d68554c635f4199";
    public static final String FREEZE="0f2583dd500a14c43a8a57d8ca83d73642d6efe626a5a539874529bd4f684c7c";
    public static final String AUDIT="61c215e3878be2663cff7f7f7316ec718a66652a2554cd3a495af5e01a177a05";
    public static final String CONTAINER="a1fb007aa25feeed4c48608d738606389fc585a222764ddb78d4d55e962a43c5";
    public static final String DATABASE="spec_agent_p3b_live_rag_d3_recovery_20261023";
    public static final Set<String> REPAIR_FILES=Set.of(
        "backend/build.gradle.kts",
        "backend/src/test/java/com/specagent/retrieval/SharedRetrievalIntegrationTest.java",
        "backend/src/test/java/com/specagent/retrieval/eval/RetrievalEvaluationSnapshot.java",
        "backend/src/test/java/com/specagent/retrieval/eval/RetrievalEvaluationSnapshotTest.java",
        "backend/src/test/java/com/specagent/retrieval/eval/RetrievalExecutionClaimManager.java",
        "backend/src/test/java/com/specagent/retrieval/eval/RetrievalEvaluationJournal.java",
        "backend/src/test/java/com/specagent/retrieval/eval/RetrievalEvaluationJournalTest.java",
        "backend/src/test/java/com/specagent/retrieval/eval/RetrievalRecoveryGate.java",
        "backend/src/test/java/com/specagent/retrieval/eval/RetrievalRecoveryGateTest.java",
        "backend/src/test/resources/retrieval/executor-smoke.v1.json");
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    public record Qualified(RetrievalEvaluationSnapshot snapshot,ExecutionClaim parent,Path ledger,String manifestSha,boolean synthetic) {
        public ExecutionClaim claim() { return claimRecovery(parent,manifestSha,ledger); }
    }
    private RetrievalRecoveryGate() {}
    public static Qualified open(Path root,Path manifestPath,String authorizedSha,Map<String,Object> environment,boolean synthetic) {
        root=root.toAbsolutePath().normalize(); manifestPath=manifestPath.toAbsolutePath().normalize();
        require(authorizedSha!=null && authorizedSha.matches("[0-9a-f]{64}"),"Explicit immutable manifest authorization required");
        require(fileSha(manifestPath).equals(authorizedSha),"Recovery manifest SHA mismatch");
        JsonNode m=parse(read(manifestPath));
        require("RECOVERY_RUN".equals(m.path("mode").asText()) && m.path("maximumExecutions").isInt() && m.path("maximumExecutions").asInt()==1,"Recovery execution limit/mode");
        require((synthetic?"SYNTHETIC_EXECUTOR_RECOVERY":"FORMAL_RAG_D3_RECOVERY").equals(m.path("scope").asText()),"Recovery scope mismatch");
        if(synthetic) for(String key:List.of("candidateFile","freezeFile","auditFile"))
            require(inside(root,m.path(key).asText()).startsWith(root.resolve("scratch")),"Synthetic must not read formal candidate");
        require(m.path("environment").equals(JSON.valueToTree(environment)),"Actual environment differs from manifest");
        require(m.path("head").asText().equals(head(root)),"HEAD drift");
        JsonNode f=parse(read(inside(root,m.path("freezeFile").asText())));
        String raw=read(inside(root,m.path("candidateFile").asText())),audit=read(inside(root,m.path("auditFile").asText()));
        require(Hashes.sha256Hex(raw).equals(m.path("candidateSha256").asText()),"Candidate identity");
        require(fileSha(inside(root,m.path("freezeFile").asText())).equals(m.path("freezeSha256").asText()),"Original freeze identity");
        require(Hashes.sha256Hex(audit).equals(m.path("auditSha256").asText()),"Audit identity");
        require(f.path("datasetSha256").asText().equals(m.path("candidateSha256").asText()),"Frozen candidate binding");
        require(m.path("metricContract").equals(f.path("metricContract")) && m.path("thresholds").equals(f.path("proposedThresholds")),"Scoring contract drift");
        require(m.path("retrievalIdentity").equals(retrievalIdentity(f)),"Model/Profile/retrieval contract drift");
        Path ledger=inside(root,m.path("ledgerDirectory").asText());
        ExecutionClaim parent=parent(m,ledger);
        require(parent.datasetSha256().equals(m.path("candidateSha256").asText()) && parent.freezeSha256().equals(m.path("freezeSha256").asText()),"Parent data/freeze binding");
        require(!Files.exists(ledger.resolve(recoveryFamily(parent)+".claim.json")),"Recovery already consumed, including interruption");
        JsonNode data=parse(raw);
        if(synthetic) {
            require(!PARENT.equals(parent.runId()) && ledger.startsWith(root.resolve("scratch")),"Synthetic cannot use formal history");
            require("EXECUTOR_SYNTHETIC_SMOKE".equals(data.path("datasetRole").asText()) && data.path("queries").size()==3,"Synthetic fixture boundary");
            for(String key:List.of("candidateFile","freezeFile","auditFile")) require(inside(root,m.path(key).asText()).startsWith(root.resolve("scratch")),"Synthetic must not read formal candidate");
        } else {
            require(PARENT.equals(parent.runId()) && "rag-d3-v2-68".equals(parent.evaluationFamilyId()),"Original formal run binding");
            require(CANDIDATE.equals(m.path("candidateSha256").asText()) && FREEZE.equals(m.path("freezeSha256").asText()) && AUDIT.equals(m.path("auditSha256").asText()),"Original immutable formal identities");
            require(ledger.equals(root.resolve("data/retrieval-eval-claims")),"Formal ledger location");
            require(m.path("parentClaimSha256").asText().equals("336d83b10b234f6fcec8f1feecb0bdafff3b6b83a93955dd8c143a517418b4bd")
                && m.path("parentReceiptSha256").asText().equals("0c0fa6ac945c722c3e6576cfd82a84e136007a152525f3708e78b344ad13441e"),"Original claim/receipt bytes");
            require(fileSha(ledger.resolve(PARENT+".result.json")).equals("3ef2da2e2c2b294e260a2e6469debefe5e1d956e51bdfaa644f37d84ca2e020a"),"Original result bytes");
            require("jdbc:postgresql://127.0.0.1:55432/".concat(DATABASE).equals(m.path("environment").path("jdbcUrl").asText()),"Dedicated recovery JDBC target");
            require(DATABASE.equals(m.path("environment").path("database").path("database").asText()),"Dedicated database name");
        }
        validateEnvironment(m.path("environment"));
        Map<String,String> repairs=validateCode(root,m,f,synthetic);
        var snapshot=new RetrievalEvaluationSnapshot(Path.of(m.path("candidateFile").asText()).getFileName().toString(),raw,read(inside(root,m.path("freezeFile").asText())),audit,true);
        if(!synthetic) snapshot.validate(root,repairs);
        return new Qualified(snapshot,parent,ledger,authorizedSha,synthetic);
    }
    public static Map<String,String> validateCode(Path root,JsonNode m,JsonNode frozen,boolean synthetic) {
        Set<String> expected=new HashSet<>(); frozen.path("codeHashes").fieldNames().forEachRemaining(expected::add);
        if(!synthetic) expected.addAll(REPAIR_FILES);
        Set<String> actual=new HashSet<>(); m.path("codeHashes").fieldNames().forEachRemaining(actual::add);
        require(expected.equals(actual),"Incomplete or unexpected code identity set");
        if(!synthetic) {
            try {
                var git=new ProcessBuilder("git","-C",root.toAbsolutePath().toString(),"status","--porcelain","--untracked-files=all","--","backend","agent-brain","frontend").start();
                String status=new String(git.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                require(git.waitFor()==0,"Cannot verify unlisted code changes");
                for(String line:status.lines().toList()) if(line.length()>3) {
                    String file=line.substring(3).replace('\\','/');
                    if(file.startsWith("backend/src/") || file.startsWith("agent-brain/src/") || file.startsWith("agent-brain/scripts/") || file.startsWith("frontend/src/"))
                        require(expected.contains(file),"Unlisted added/renamed code: "+file);
                }
            } catch(IOException ex) { throw new UncheckedIOException(ex); }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        }
        Path diff=inside(root,m.path("byteDiffFile").asText());
        require(fileSha(diff).equals(m.path("byteDiffSha256").asText()),"Exact byte diff artifact drift");
        JsonNode changes=parse(read(diff)); Set<String> changed=new HashSet<>(); var repairs=new TreeMap<String,String>();
        for(String file:expected) {
            String current=fileSha(inside(root,file)),before=frozen.path("codeHashes").path(file).asText("ABSENT");
            require(current.equals(m.path("codeHashes").path(file).asText()),"Actual code hash mismatch: "+file);
            if(!current.equals(before)) {
                require(REPAIR_FILES.contains(file),"Non-allowlisted code drift: "+file);
                JsonNode c=changes.path(file); require(c.isObject(),"Missing exact diff: "+file);
                require(before.equals(c.path("beforeSha256").asText()) && current.equals(c.path("afterSha256").asText()),"Diff identity: "+file);
                byte[] after=Base64.getDecoder().decode(c.path("afterBase64").asText());
                require(Arrays.equals(after,bytes(inside(root,file))),"Diff does not contain current bytes: "+file);
                byte[] old=Base64.getDecoder().decode(c.path("beforeBase64").asText());
                require(before.equals("ABSENT")?old.length==0:hash(old).equals(before),"Diff does not contain frozen bytes: "+file);
                require(!c.path("reason").asText().isBlank(),"Repair reason required");
                changed.add(file); repairs.put(file,current);
            }
        }
        Set<String> listed=new HashSet<>(); changes.fieldNames().forEachRemaining(listed::add);
        require(changed.equals(listed),"Unlisted/extra repair differences");
        return repairs;
    }
    private static ExecutionClaim parent(JsonNode m,Path ledger) {
        String family=m.path("parentFamilyId").asText(),id=m.path("parentRunId").asText();
        require(family.matches("[A-Za-z0-9_-]{1,128}") && id.matches("[0-9a-f-]{36}"),"Parent identity format");
        Path claim=ledger.resolve(family+".claim.json"),receipt=ledger.resolve(m.path("candidateSha256").asText()+"."+id+".receipt.json");
        require(fileSha(claim).equals(m.path("parentClaimSha256").asText()) && fileSha(receipt).equals(m.path("parentReceiptSha256").asText()),"Original history changed/missing");
        try {
            ExecutionClaim p=JSON.readValue(read(claim),ExecutionClaim.class),r=JSON.readValue(read(receipt),ExecutionClaim.class);
            require(p.runId().equals(id) && p.evaluationFamilyId().equals(family) && p.status().equals("CLAIMED"),"Original claim required");
            require(r.runId().equals(id) && r.evaluationFamilyId().equals(family) && r.status().equals("INTERRUPTED_OR_ERROR")
                && r.datasetSha256().equals(p.datasetSha256()) && r.freezeSha256().equals(p.freezeSha256()),"Parent must be interrupted, never PASS/FAIL");
            return p;
        } catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
    private static void validateEnvironment(JsonNode e) {
        require(CONTAINER.equals(e.path("containerId").asText()),"Isolated container identity");
        require("172.24.0.2/32".equals(e.path("database").path("server_address").asText()) && e.path("database").path("server_port").asInt()==5432,"Server identity");
        require("spec_agent_iso_20261022".equals(e.path("database").path("connection_user").asText()),"Connection user identity");
        require("http://127.0.0.1:11434".equals(e.path("ollamaUrl").asText()) && "ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d".equals(e.path("actualModelDigest").asText())
            && e.path("actualDimensions").asInt()==1024,"Actual local embedding identity");
    }
    public static ObjectNode manifest(Path root,Path candidate,Path freeze,Path audit,ExecutionClaim parent,Path ledger,Path diff,Map<String,Object> environment,boolean synthetic) {
        JsonNode f=parse(read(freeze)); var m=JSON.createObjectNode();
        m.put("mode","RECOVERY_RUN"); m.put("maximumExecutions",1); m.put("scope",synthetic?"SYNTHETIC_EXECUTOR_RECOVERY":"FORMAL_RAG_D3_RECOVERY");
        m.put("head",head(root)); m.put("candidateFile",relative(root,candidate)); m.put("freezeFile",relative(root,freeze)); m.put("auditFile",relative(root,audit));
        m.put("candidateSha256",fileSha(candidate)); m.put("freezeSha256",fileSha(freeze)); m.put("auditSha256",fileSha(audit));
        m.put("parentRunId",parent.runId()); m.put("parentFamilyId",parent.evaluationFamilyId()); m.put("ledgerDirectory",relative(root,ledger));
        m.put("parentClaimSha256",fileSha(ledger.resolve(parent.evaluationFamilyId()+".claim.json")));
        m.put("parentReceiptSha256",fileSha(ledger.resolve(parent.datasetSha256()+"."+parent.runId()+".receipt.json")));
        m.set("metricContract",f.path("metricContract")); m.set("thresholds",f.path("proposedThresholds")); m.set("environment",JSON.valueToTree(environment));
        m.set("retrievalIdentity",retrievalIdentity(f));
        m.put("byteDiffFile",relative(root,diff)); m.put("byteDiffSha256",fileSha(diff));
        var code=m.putObject("codeHashes"); f.path("codeHashes").fieldNames().forEachRemaining(name->code.put(name,fileSha(inside(root,name))));
        if(!synthetic) REPAIR_FILES.stream().sorted().forEach(name->code.put(name,fileSha(inside(root,name))));
        return m;
    }
    private static ObjectNode retrievalIdentity(JsonNode frozen) {
        var identity=JSON.createObjectNode();
        for(String key:List.of("modelTag","modelDigest","dimensions","profileId","rrfK","maxVectorDistance","maxItems","maxChars","thresholdStatus","noAnswerPolicy"))
            if(frozen.has(key)) identity.set(key,frozen.path(key));
        return identity;
    }
    public static void writeImmutable(Path path,Object value) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            byte[] bytes=JSON.writeValueAsBytes(value);
            try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
        } catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
    /** Explicit synthetic history only. Never opens a formal dataset/freeze/audit/claim. */
    public static Path syntheticFixture(Path root,Path directory,String raw,Map<String,Object> environment) {
        try { Files.createDirectories(directory); } catch(IOException ex) { throw new UncheckedIOException(ex); }
        Path candidate=directory.resolve("executor-smoke.v1.json"),freeze=directory.resolve("synthetic-freeze.json"),audit=directory.resolve("synthetic-audit.json"),diff=directory.resolve("byte-diff.json"),ledger=directory.resolve("ledger");
        try { Files.writeString(candidate,raw,StandardOpenOption.CREATE_NEW); } catch(IOException ex) { throw new UncheckedIOException(ex); }
        var f=JSON.createObjectNode(); f.put("datasetSha256",Hashes.sha256Hex(raw));
        f.put("modelTag","qwen3-embedding:0.6b"); f.put("modelDigest","ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d");
        f.put("dimensions",1024); f.put("profileId","26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047");
        f.put("rrfK",60); f.put("maxVectorDistance",0.50); f.put("maxItems",8); f.put("maxChars",8000);
        f.put("thresholdStatus","AI_BASELINE_FROZEN"); f.put("noAnswerPolicy","DIAGNOSTIC_RETRIEVAL_NOISE");
        f.set("metricContract",parse("{\"mode\":\"AI_DIAGNOSTIC_SYNTHETIC_FIXTURE\"}"));
        f.set("proposedThresholds",parse("{\"macroSourceRecallAt8\":0.85,\"firstRelevantMrr\":0.75,\"multiSourceAllOfCoverage\":0.75,\"staleSourceLeakCount\":0,\"permissionLeakCount\":0,\"sourceIdentityValidity\":1.0}"));
        var code=f.putObject("codeHashes"); REPAIR_FILES.stream().sorted().forEach(file->code.put(file,fileSha(inside(root,file))));
        writeImmutable(freeze,f); writeImmutable(audit,Map.of("syntheticOnly",true,"humanApproval",false)); writeImmutable(diff,Map.of());
        var parent=claimFirstRun("synthetic-parent-"+UUID.randomUUID(),fileSha(candidate),fileSha(freeze),ledger);
        recordOutcome(parent,"INTERRUPTED_OR_ERROR","CONSTRUCTED_SYNTHETIC_PARENT_HISTORY; no formal query executed",ledger);
        Path manifest=directory.resolve("recovery-authorization.json");
        writeImmutable(manifest,manifest(root,candidate,freeze,audit,parent,ledger,diff,environment,true)); return manifest;
    }
    public static String head(Path root) {
        try {
            var process=new ProcessBuilder("git","-C",root.toAbsolutePath().toString(),"rev-parse","HEAD").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();
            require(process.waitFor()==0 && result.matches("[0-9a-f]{40}"),"Cannot identify HEAD"); return result;
        } catch(IOException ex) { throw new UncheckedIOException(ex); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
    public static String relative(Path root,Path path) { return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\','/'); }
    public static Path inside(Path root,String file) { Path p=root.toAbsolutePath().resolve(file).normalize(); require(p.startsWith(root.toAbsolutePath().normalize()),"Path escapes repository"); return p; }
    private static String read(Path p) { try { return Files.readString(p); } catch(IOException ex) { throw new UncheckedIOException(ex); } }
    private static byte[] bytes(Path p) { try { return Files.readAllBytes(p); } catch(IOException ex) { throw new UncheckedIOException(ex); } }
    private static String hash(byte[] b) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b)); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private static void require(boolean condition,String reason) { if(!condition) throw new IllegalArgumentException("BLOCKED: "+reason); }
}
