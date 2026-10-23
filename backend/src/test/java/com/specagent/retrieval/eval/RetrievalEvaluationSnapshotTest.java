package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only historical snapshots. The interrupted freeze must reject the repaired executor. */
class RetrievalEvaluationSnapshotTest {
    static final Path ROOT=Path.of("..");
    static final String FILE="acceptance-candidate.v2.3.json";
    // Reading history is separate from load(), which qualifies an executable snapshot.
    private RetrievalEvaluationSnapshot historical() throws Exception {
        Path data=ROOT.resolve("contracts/retrieval/evaluation");
        return new RetrievalEvaluationSnapshot(FILE,Files.readString(data.resolve(FILE)),
            Files.readString(data.resolve("acceptance-freeze.v2.3.json")),
            Files.readString(data.resolve("ai-review-decisions.v1.json")),true);
    }
    @Test void interruptedFreezeRejectsRepairedCodeInsteadOfRenewingEligibility() {
        var failure=assertThrows(RetrievalAcceptanceGateValidator.GateValidationException.class,()->
            RetrievalEvaluationSnapshot.load(ROOT,FILE,RetrievalEvaluationSnapshot.AI_MODE));
        assertTrue(failure.getMessage().contains("SharedRetrievalIntegrationTest.java"));
        assertThrows(IllegalArgumentException.class,()->RetrievalEvaluationSnapshot.load(ROOT,FILE,RetrievalEvaluationSnapshot.HUMAN_MODE));
    }
    @Test void authorizationCannotSubstituteForQualification() throws Exception {
        var snapshot=historical(); assertTrue(snapshot.aiAudited());
        assertThrows(IllegalArgumentException.class,()->snapshot.requireRunAuthorization(null));
        assertDoesNotThrow(()->snapshot.requireRunAuthorization(snapshot.datasetSha256()+":"+snapshot.freezeSha256()));
        assertThrows(RetrievalAcceptanceGateValidator.GateValidationException.class,()->snapshot.validate(ROOT));
        assertEquals("PENDING",snapshot.dataset().path("humanReviewStatus").asText());
        assertEquals("rag-d3-v2-68",snapshot.familyId());
    }
    @Test void historicalSnapshotsDoNotExposeMutableJsonState() throws Exception {
        var snapshot=historical();
        ((ObjectNode)snapshot.dataset()).put("executionStatus","EXECUTED");
        ((ObjectNode)snapshot.freeze()).put("rrfK",100);
        assertEquals("NOT_RUN",snapshot.dataset().path("executionStatus").asText());
        assertEquals(60,snapshot.freeze().path("rrfK").asInt());
    }
    @Test void historicalInputsAndAuditRemainByteIdentical() throws Exception {
        var snapshot=historical();
        assertEquals("eb1262001f1f67a28c6210a6cd7846b8b7adf547de3453096d68554c635f4199",snapshot.datasetSha256());
        assertEquals("0f2583dd500a14c43a8a57d8ca83d73642d6efe626a5a539874529bd4f684c7c",snapshot.freezeSha256());
        assertEquals("61c215e3878be2663cff7f7f7316ec718a66652a2554cd3a495af5e01a177a05",com.specagent.common.Hashes.sha256Hex(snapshot.auditRaw()));
    }
    @Test void originalConsumedClaimCannotBeRenewedEvenInATemporaryLedger(@TempDir Path directory) throws Exception {
        // Temp fixture; no local ledger modification or real candidate execution.
        Files.writeString(directory.resolve("rag-d3-v2-68.claim.json"),"{\"runId\":\"2a419207-4cf2-4e3d-9361-0eb4219a6199\",\"status\":\"CLAIMED\"}");
        assertThrows(RetrievalExecutionClaimManager.ClaimCollisionException.class,()->
            RetrievalExecutionClaimManager.claimFirstRun("rag-d3-v2-68","changed-data","changed-freeze",directory));
    }
    @Test void absentFilesUnknownModesAndMalformedJsonFailClosed(@TempDir Path directory) throws Exception {
        assertThrows(java.io.UncheckedIOException.class,()->RetrievalEvaluationSnapshot.load(directory,FILE,RetrievalEvaluationSnapshot.AI_MODE));
        assertThrows(IllegalArgumentException.class,()->RetrievalEvaluationSnapshot.load(ROOT,"executor-smoke.v1.json",RetrievalEvaluationSnapshot.AI_MODE));
        assertThrows(IllegalArgumentException.class,()->RetrievalEvaluationSnapshot.load(ROOT,FILE,"RECOVERY_RUN"));
        var data=Files.createDirectories(directory.resolve("contracts/retrieval/evaluation"));
        Files.copy(ROOT.resolve("contracts/retrieval/evaluation/"+FILE),data.resolve(FILE));
        assertThrows(java.io.UncheckedIOException.class,()->RetrievalEvaluationSnapshot.load(directory,FILE,RetrievalEvaluationSnapshot.AI_MODE));
        for(String raw:java.util.List.of("{","{} {}","{\"x\":1,\"x\":2}","{\"x\":NaN}","[]"))
            assertThrows(RuntimeException.class,()->RetrievalEvaluationSnapshot.parse(raw));
    }
    @Test void frozenPythonInventoryRemainsAnOfflineMetadataCheck() throws Exception {
        var snapshot=historical();
        assertThrows(IllegalArgumentException.class,()->snapshot.requireRuntime(ROOT,null));
        var inventory=RetrievalEvaluationSnapshot.parse(Files.readString(ROOT.resolve("contracts/retrieval/evaluation/runtime-inventory.v1.json")));
        assertDoesNotThrow(()->snapshot.requireRuntime(ROOT,inventory.path("pythonExecutable").asText()));
    }
}
