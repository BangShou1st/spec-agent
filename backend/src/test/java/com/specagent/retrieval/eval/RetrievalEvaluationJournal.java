package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.specagent.retrieval.eval.RetrievalExecutionClaimManager.*;

/** Test executor journal. Every file is CREATE_NEW + fsync; raw returns precede all interpretation. */
public final class RetrievalEvaluationJournal {
    public enum Mode {
        DEVELOPMENT_CALIBRATION(true), HUMAN_APPROVED_INDEPENDENT_ACCEPTANCE(true),
        AI_AUDITED_SYNTHETIC_EVALUATION(false), EXECUTOR_SYNTHETIC_SMOKE(false), RECOVERY_RUN(false);
        private final boolean baseline;
        Mode(boolean baseline) { this.baseline=baseline; }
        public boolean requiresLegacyBaseline() { return baseline; }
    }
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
    private final ExecutionClaim claim;
    private final Path directory, artifacts;
    private final Mode mode;
    private final List<Object> returns=new ArrayList<>(), scored=new ArrayList<>();
    private final List<String> failures=new ArrayList<>();
    private Map<String,Object> quality;
    private boolean passed, bodyFinished;
    private int events, started, returned;

    public RetrievalEvaluationJournal(ExecutionClaim claim, Path directory, Mode mode, Map<String,Object> environment) {
        this.claim=Objects.requireNonNull(claim); this.directory=directory; this.mode=Objects.requireNonNull(mode);
        artifacts=directory.resolve(claim.runId()+".artifacts");
        try { Files.createDirectory(artifacts); } catch(IOException ex) { throw new UncheckedIOException(ex); }
        artifact("manifest.json",Map.of("identity",identity(),"environment",environment));
    }
    private Map<String,Object> identity() {
        var value=new LinkedHashMap<String,Object>();
        value.putAll(Map.of("runId",claim.runId(),"datasetSha256",claim.datasetSha256(),"freezeSha256",claim.freezeSha256(),
            "evaluationFamilyId",claim.evaluationFamilyId(),"evaluationMode",mode.name()));
        if(mode==Mode.RECOVERY_RUN) value.put("recoveryLink",RetrievalEvaluationSnapshot.parse(claim.outcomeDetails()));
        return value;
    }
    public Path artifactsDirectory() { return artifacts; }
    public void stage(String name, Object detail) {
        artifact(String.format("event-%03d.json",++events),Map.of("stage",name,"detail",detail));
    }
    public void queryStarted(int ordinal,String id,Object query) {
        artifact(queryFile(ordinal,id,"started"),Map.of("ordinal",ordinal,"queryId",id,"query",query)); started++;
    }
    public void queryReturned(int ordinal,String id,long elapsedMs,Object rawTop8,Object aliases) {
        returned++;
        var value=new LinkedHashMap<String,Object>(); value.put("ordinal",ordinal); value.put("queryId",id);
        value.put("elapsedMs",elapsedMs); value.put("rawResult",rawTop8); value.put("fixtureAliases",aliases);
        // Retain an immutable in-memory fallback as well if durable I/O itself fails.
        Object snapshot;
        try { snapshot=snapshot(value); }
        catch(RuntimeException serializationFailure) {
            value.put("rawResult",String.valueOf(rawTop8)); value.put("fixtureAliases",String.valueOf(aliases));
            value.put("serializationFailure",serializationFailure.toString());
            snapshot=snapshot(value); returns.add(snapshot); artifact(queryFile(ordinal,id,"returned"),snapshot);
            throw serializationFailure;
        }
        returns.add(snapshot);
        artifact(queryFile(ordinal,id,"returned"),snapshot);
    }
    public void queryScored(int ordinal,String id,Object observation) {
        Object snapshot=snapshot(observation); scored.add(snapshot);
        artifact(queryFile(ordinal,id,"scored"),snapshot);
    }
    public void prepareQuality(Map<String,Object> report,boolean passed) {
        this.quality=JSON.convertValue(snapshot(report),Map.class); this.passed=passed;
        // A checkpoint is explicitly nonterminal, even if the quality gate passes.
        var checkpoint=new LinkedHashMap<>(quality); checkpoint.put("verdict","PENDING_CLEANUP");
        artifact("quality-checkpoint.json",checkpoint);
    }
    public void bodyFinished() { bodyFinished=true; stage("BODY_FINISHED",Map.of()); }
    public void failed(Throwable failure) { failures.add(failure.getClass().getName()+": "+failure.getMessage()); }
    public Map<String,Object> finishAfterCleanup() {
        var report=new LinkedHashMap<String,Object>(); if(quality!=null) report.putAll(quality);
        report.putAll(identity()); report.put("startedQueries",started); report.put("returnedQueries",returned);
        report.put("scoredQueries",scored.size()); report.put("rawReturns",List.copyOf(returns));
        report.put("observations",List.copyOf(scored)); report.put("failureReasons",List.copyOf(failures));
        boolean complete=bodyFinished && quality!=null && failures.isEmpty();
        String terminal=complete?(passed?"COMPLETED_PASS":"COMPLETED_FAIL"):"INTERRUPTED_OR_ERROR";
        report.put("verdict",complete?(passed?"PASS":"FAIL"):terminal);
        report.put("executionStatus",terminal); report.put("bodyFinished",bodyFinished);
        report.put("finishedAt",Instant.now().toString()); report.put("artifactsPath",artifacts.toAbsolutePath().normalize().toString());
        // Persist all evidence first, explicitly without a terminal verdict. The single final CREATE_NEW
        // receipt is the authority and carries the complete final report. Receipt failure cannot leave PASS.
        var pending=new LinkedHashMap<>(report);
        pending.put("qualityVerdict",quality==null?"NOT_SCORED":passed?"PASS":"FAIL");
        pending.put("verdict","PENDING_TERMINAL_RECEIPT"); pending.put("executionStatus","PENDING_TERMINAL_RECEIPT");
        pending.put("terminalReceiptPath",directory.resolve(claim.datasetSha256()+"."+claim.runId()+".receipt.json").toAbsolutePath().normalize().toString());
        writeResult(claim,json(pending),directory);
        recordOutcome(claim,terminal,json(report),directory);
        return report;
    }
    private static String queryFile(int ordinal,String id,String suffix) {
        if(ordinal<1 || id==null || !id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Unsafe query identity");
        return String.format("query-%03d-%s.%s.json",ordinal,id,suffix);
    }
    private Object snapshot(Object value) { return JSON.valueToTree(value); }
    private void artifact(String file,Object value) {
        byte[] bytes=json(Map.of("identity",identity(),"recordedAt",Instant.now().toString(),"payload",value))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(artifacts.resolve(file),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            var buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        } catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
}
