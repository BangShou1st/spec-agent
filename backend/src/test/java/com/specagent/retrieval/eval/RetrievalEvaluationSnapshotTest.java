package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specagent.common.Hashes;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only/preflight and temp-ledger tests; no Spring, datasource, service process or candidate queries. */
class RetrievalEvaluationSnapshotTest {
    static final Path ROOT=Path.of("..");
    static final String FILE="acceptance-candidate.v2.3.json";

    @Test void finalAiAuditQualifiesOnlyForSeparateModeAndNeedsNewAuthorization() {
        var snapshot=RetrievalEvaluationSnapshot.load(ROOT,FILE,RetrievalEvaluationSnapshot.AI_MODE);
        assertTrue(snapshot.aiAudited());
        assertThrows(IllegalArgumentException.class,()->snapshot.requireRunAuthorization(null));
        assertDoesNotThrow(()->snapshot.requireRunAuthorization(snapshot.datasetSha256()+":"+snapshot.freezeSha256()));
        assertThrows(IllegalArgumentException.class,()->RetrievalEvaluationSnapshot.load(ROOT,FILE,RetrievalEvaluationSnapshot.HUMAN_MODE));
        assertThrows(RetrievalAcceptanceGateValidator.GateValidationException.class,()->
            RetrievalEvaluationSnapshot.load(ROOT,"acceptance-candidate.v2.2.json",RetrievalEvaluationSnapshot.HUMAN_MODE));
        assertEquals("PENDING",snapshot.dataset().path("humanReviewStatus").asText());
    }

    @Test void snapshotsDoNotExposeMutableJsonState() {
        var snapshot=RetrievalEvaluationSnapshot.load(ROOT,FILE,RetrievalEvaluationSnapshot.AI_MODE);
        ((ObjectNode)snapshot.dataset()).put("executionStatus","EXECUTED");
        ((ObjectNode)snapshot.freeze()).put("rrfK",100);
        assertEquals("NOT_RUN",snapshot.dataset().path("executionStatus").asText());
        assertEquals(60,snapshot.freeze().path("rrfK").asInt());
        assertDoesNotThrow(()->snapshot.validate(ROOT));
    }

    @Test void unresolvedOrIncompleteAuditCannotBeDisguisedByNewHash() {
        var snapshot=RetrievalEvaluationSnapshot.load(ROOT,FILE,RetrievalEvaluationSnapshot.AI_MODE);
        var audit=(ObjectNode)RetrievalEvaluationSnapshot.parse(snapshot.auditRaw());
        ((ObjectNode)audit.path("queryDecisions").get(0)).put("conclusion","AI_UNRESOLVED");
        var freeze=(ObjectNode)snapshot.freeze();
        freeze.put("aiReviewSha256",Hashes.sha256Hex(audit.toString()));
        var changed=new RetrievalEvaluationSnapshot(FILE,snapshot.datasetRaw(),freeze.toString(),audit.toString(),true);
        assertThrows(IllegalArgumentException.class,()->changed.validate(ROOT));
    }

    @Test void absentFilesAndMalformedJsonFailClosed(@TempDir Path directory) throws Exception {
        assertThrows(java.io.UncheckedIOException.class,()->RetrievalEvaluationSnapshot.load(directory,FILE,RetrievalEvaluationSnapshot.AI_MODE));
        var data=Files.createDirectories(directory.resolve("contracts/retrieval/evaluation"));
        Files.copy(ROOT.resolve("contracts/retrieval/evaluation/"+FILE),data.resolve(FILE));
        assertThrows(java.io.UncheckedIOException.class,()->RetrievalEvaluationSnapshot.load(directory,FILE,RetrievalEvaluationSnapshot.AI_MODE));
        for(String raw:java.util.List.of("{","{} {}","{\"x\":1,\"x\":2}","{\"x\":NaN}","[]"))
            assertThrows(RuntimeException.class,()->RetrievalEvaluationSnapshot.parse(raw));
    }

    @Test void invalidRuntimeAndIncompleteImplementationAreRejected() {
        var snapshot=RetrievalEvaluationSnapshot.load(ROOT,FILE,RetrievalEvaluationSnapshot.AI_MODE);
        assertThrows(IllegalArgumentException.class,()->snapshot.requireRuntime(ROOT,null));
        var freeze=(ObjectNode)snapshot.freeze();
        ((ObjectNode)freeze.path("codeHashes")).remove("backend/src/test/java/com/specagent/retrieval/eval/RetrievalExecutionClaimManager.java");
        var changed=new RetrievalEvaluationSnapshot(FILE,snapshot.datasetRaw(),freeze.toString(),snapshot.auditRaw(),true);
        assertThrows(IllegalArgumentException.class,()->changed.validate(ROOT));
    }

    @Test void frozenPythonInventoryRunsOnlyOfflineMetadataCheck() throws Exception {
        var snapshot=RetrievalEvaluationSnapshot.load(ROOT,FILE,RetrievalEvaluationSnapshot.AI_MODE);
        var inventory=RetrievalEvaluationSnapshot.parse(Files.readString(ROOT.resolve("contracts/retrieval/evaluation/runtime-inventory.v1.json")));
        assertDoesNotThrow(()->snapshot.requireRuntime(ROOT,inventory.path("pythonExecutable").asText()));
    }
}
