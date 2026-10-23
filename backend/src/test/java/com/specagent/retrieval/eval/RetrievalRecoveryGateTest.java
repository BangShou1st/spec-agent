package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.specagent.retrieval.eval.RetrievalExecutionClaimManager.*;

/** All parent histories are explicitly constructed synthetic fixtures under scratch. No datasource/model. */
class RetrievalRecoveryGateTest {
    static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON=new ObjectMapper();
    Path directory,manifest; Map<String,Object> environment;
    @BeforeEach void fixture() throws Exception {
        directory=Files.createTempDirectory(ROOT.resolve("scratch/tmp"),"recovery-offline-");
        environment=Map.of("containerId",RetrievalRecoveryGate.CONTAINER,"database",Map.of("server_address","172.24.0.2/32","server_port",5432,"connection_user","spec_agent_iso_20261022"),
            "ollamaUrl","http://127.0.0.1:11434","actualDimensions",1024,"actualModelDigest","ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d");
        manifest=RetrievalRecoveryGate.syntheticFixture(ROOT,directory,Files.readString(ROOT.resolve("backend/src/test/resources/retrieval/executor-smoke.v1.json")),environment);
    }
    RetrievalRecoveryGate.Qualified open(Path path) { return RetrievalRecoveryGate.open(ROOT,path,RetrievalEvaluationSnapshot.fileSha(path),environment,true); }
    ObjectNode value() throws Exception { return (ObjectNode)JSON.readTree(Files.readString(manifest)); }
    Path variant(ObjectNode value) { Path p=directory.resolve("variant-"+UUID.randomUUID()+".json"); RetrievalRecoveryGate.writeImmutable(p,value); return p; }
    @Test void missingParentRunIsBlocked() throws Exception {
        var m=value(); m.put("parentRunId",UUID.randomUUID().toString()); assertThrows(RuntimeException.class,()->open(variant(m)));
    }
    @Test void completedParentCannotBeRecovered() throws Exception {
        var m=value(); Path receipt=ROOT.resolve(m.path("ledgerDirectory").asText()).resolve(m.path("candidateSha256").asText()+"."+m.path("parentRunId").asText()+".receipt.json");
        // Deliberate tampering of this test's own constructed fixture; never a real execution receipt.
        var r=(ObjectNode)JSON.readTree(Files.readString(receipt)); r.put("status","COMPLETED_PASS"); Files.writeString(receipt,r.toString());
        m.put("parentReceiptSha256",RetrievalEvaluationSnapshot.fileSha(receipt));
        var failure=assertThrows(IllegalArgumentException.class,()->open(variant(m))); assertTrue(failure.getMessage().contains("Parent must be interrupted"));
    }
    @Test void missingOriginalClaimIsBlocked() throws Exception {
        var m=value(); m.put("parentFamilyId","absent-parent"); assertThrows(RuntimeException.class,()->open(variant(m)));
    }
    @Test void missingManifestIsBlocked() { assertThrows(RuntimeException.class,()->RetrievalRecoveryGate.open(ROOT,directory.resolve("absent.json"),"a".repeat(64),environment,true)); }
    @Test void wrongAuthorizationAndActualCodeHashesAreBlocked() throws Exception {
        assertThrows(IllegalArgumentException.class,()->RetrievalRecoveryGate.open(ROOT,manifest,"a".repeat(64),environment,true));
        var m=value(); ((ObjectNode)m.path("codeHashes")).put("backend/build.gradle.kts","b".repeat(64));
        assertThrows(IllegalArgumentException.class,()->open(variant(m)));
    }
    @Test void nonAllowlistedCodeDriftCannotBeAuthorizedByAManifest() throws Exception {
        Path file=directory.resolve("forbidden-algorithm.java"); Files.writeString(file,"changed runtime algorithm");
        var m=value(); Path frozen=ROOT.resolve(m.path("freezeFile").asText());
        var f=(ObjectNode)JSON.readTree(Files.readString(frozen)); String name=RetrievalRecoveryGate.relative(ROOT,file);
        ((ObjectNode)f.path("codeHashes")).put(name,com.specagent.common.Hashes.sha256Hex("original runtime algorithm"));
        Path newFreeze=directory.resolve("tampered-synthetic-freeze.json"); RetrievalRecoveryGate.writeImmutable(newFreeze,f);
        m.put("freezeFile",RetrievalRecoveryGate.relative(ROOT,newFreeze)); m.put("freezeSha256",RetrievalEvaluationSnapshot.fileSha(newFreeze));
        ((ObjectNode)m.path("codeHashes")).put(name,RetrievalEvaluationSnapshot.fileSha(file));
        // Isolate code validator so the constructed parent binding need not be forged for this negative case.
        var failure=assertThrows(IllegalArgumentException.class,()->RetrievalRecoveryGate.validateCode(ROOT,m,f,true));
        assertTrue(failure.getMessage().contains("Non-allowlisted"));
    }
    @Test void duplicateAndInterruptedRecoveryRemainConsumed() throws Exception {
        var q=open(manifest); var recovery=q.claim();
        assertNotEquals(q.parent().runId(),recovery.runId());
        recordOutcome(recovery,"INTERRUPTED_OR_ERROR","injected synthetic interruption",q.ledger());
        assertThrows(ClaimCollisionException.class,q::claim);
        assertThrows(IllegalArgumentException.class,()->open(manifest));
        assertEquals("CLAIMED",JSON.readTree(Files.readString(q.ledger().resolve(recovery.evaluationFamilyId()+".claim.json"))).path("status").asText());
    }
    @Test void concurrentRecoveryHasExactlyOneWinner() throws Exception {
        var q=open(manifest); var start=new CountDownLatch(1); var winners=new java.util.concurrent.atomic.AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<8;i++) tasks.add(pool.submit(()->{ try { start.await(); q.claim(); winners.incrementAndGet(); }
                catch(ClaimCollisionException expected) {} catch(InterruptedException ex) { throw new RuntimeException(ex); } }));
            start.countDown(); for(var task:tasks) task.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,winners.get());
    }
    @Test void finalResultAndReceiptCannotBeOverwrittenAndParentBytesSurvive() throws Exception {
        var q=open(manifest); Path parent=q.ledger().resolve(q.parent().evaluationFamilyId()+".claim.json"); byte[] old=Files.readAllBytes(parent);
        var recovery=q.claim(); writeResult(recovery,"{\"fixture\":true}",q.ledger()); recordOutcome(recovery,"COMPLETED_PASS","synthetic unit fixture",q.ledger());
        assertThrows(java.io.UncheckedIOException.class,()->writeResult(recovery,"overwrite",q.ledger()));
        assertThrows(IllegalStateException.class,()->recordOutcome(recovery,"COMPLETED_FAIL","overwrite",q.ledger()));
        assertArrayEquals(old,Files.readAllBytes(parent)); assertThrows(ClaimCollisionException.class,q::claim);
    }
    @Test void modelDatabaseContractAndScopeDriftAreBlocked() throws Exception {
        var m=value(); ((ObjectNode)m.path("thresholds")).put("macroSourceRecallAt8",0.1); Path wrongThreshold=variant(m); assertThrows(IllegalArgumentException.class,()->open(wrongThreshold));
        m=value(); m.put("scope","FORMAL_RAG_D3_RECOVERY"); Path wrongScope=variant(m); assertThrows(IllegalArgumentException.class,()->open(wrongScope));
        var altered=new HashMap<>(environment); altered.put("actualDimensions",768);
        assertThrows(IllegalArgumentException.class,()->RetrievalRecoveryGate.open(ROOT,manifest,RetrievalEvaluationSnapshot.fileSha(manifest),altered,true));
    }
}
