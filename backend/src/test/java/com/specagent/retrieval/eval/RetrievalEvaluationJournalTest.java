package com.specagent.retrieval.eval;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.specagent.retrieval.eval.RetrievalExecutionClaimManager.*;

class RetrievalEvaluationJournalTest {
    @TempDir Path directory;
    RetrievalEvaluationJournal journal(ExecutionClaim claim) {
        return new RetrievalEvaluationJournal(claim,directory,RetrievalEvaluationJournal.Mode.EXECUTOR_SYNTHETIC_SMOKE,Map.of("boundary","synthetic unit fixture"));
    }
    @Test void firstRawReturnSurvivesSecondCrashAndCannotBeOverwritten() throws Exception {
        var claim=claimFirstRun("synthetic","dataset","contract",directory); var run=journal(claim);
        run.queryStarted(1,"Q1",Map.of("query","independent synthetic one"));
        var raw=new ArrayList<>(List.of(Map.of("rank",1,"sourceRef","synthetic:one")));
        run.queryReturned(1,"Q1",42,raw,Map.of("one","SOURCE_ONE")); raw.clear();
        Path returned=run.artifactsDirectory().resolve("query-001-Q1.returned.json");
        String original=Files.readString(returned); assertTrue(original.contains("synthetic:one"));
        assertFalse(Files.exists(directory.resolve(claim.runId()+".result.json")));
        run.queryStarted(2,"Q2",Map.of("query","independent synthetic two"));
        run.failed(new IllegalStateException("INJECTED_SECOND_QUERY_CRASH"));
        var report=run.finishAfterCleanup();
        assertEquals("INTERRUPTED_OR_ERROR",report.get("executionStatus"));
        assertEquals(1,report.get("returnedQueries")); assertEquals(2,report.get("startedQueries"));
        assertEquals(original,Files.readString(returned));
        String finalReport=Files.readString(directory.resolve(claim.runId()+".result.json"));
        assertTrue(finalReport.contains("synthetic:one")); assertTrue(hasOutcome(claim,directory));
        assertThrows(java.io.UncheckedIOException.class,run::finishAfterCleanup);
        assertThrows(IllegalStateException.class,()->recordOutcome(claim,"COMPLETED_PASS","overwrite",directory));
        assertThrows(ClaimCollisionException.class,()->claimFirstRun("synthetic","dataset","contract",directory));
        assertEquals(finalReport,Files.readString(directory.resolve(claim.runId()+".result.json")));
    }
    @Test void qualityPassIsNotTerminalUntilBodyAndCleanupSucceed() throws Exception {
        var claim=claimFirstRun("pass","dataset","contract",directory); var run=journal(claim);
        run.prepareQuality(Map.of("recallAt8",1.0),true);
        assertFalse(hasOutcome(claim,directory));
        assertTrue(Files.readString(run.artifactsDirectory().resolve("quality-checkpoint.json")).contains("PENDING_CLEANUP"));
        run.bodyFinished();
        assertEquals("PASS",run.finishAfterCleanup().get("verdict"));
    }
    @Test void qualityFailureIsCompleteAndKeepsItsMetrics() {
        var claim=claimFirstRun("fail","dataset","contract",directory); var run=journal(claim);
        run.prepareQuality(Map.of("recallAt8",0.25,"qualityReport",Map.of("failureReasons",List.of("recall below threshold"))),false);
        run.bodyFinished(); var result=run.finishAfterCleanup();
        assertEquals("COMPLETED_FAIL",result.get("executionStatus")); assertEquals(0.25,result.get("recallAt8"));
    }
    @Test void cleanupOrSamplerFailureCannotPublishPass() {
        var claim=claimFirstRun("cleanup","dataset","contract",directory); var run=journal(claim);
        run.prepareQuality(Map.of("recallAt8",1.0),true); run.bodyFinished();
        run.failed(new IllegalStateException("INJECTED_CLEANUP_FAILURE"));
        var result=run.finishAfterCleanup(); assertEquals("INTERRUPTED_OR_ERROR",result.get("verdict"));
        assertTrue(result.get("failureReasons").toString().contains("INJECTED_CLEANUP_FAILURE"));
    }
    @Test void missingBodyCompletionAndUnsafeOrDuplicateArtifactsFailClosed() {
        var claim=claimFirstRun("unfinished","dataset","contract",directory); var run=journal(claim);
        assertThrows(IllegalArgumentException.class,()->run.queryStarted(1,"../unsafe",Map.of()));
        run.queryStarted(1,"Q1",Map.of());
        assertThrows(java.io.UncheckedIOException.class,()->run.queryStarted(1,"Q1",Map.of()));
        run.prepareQuality(Map.of("recallAt8",1.0),true);
        assertEquals("INTERRUPTED_OR_ERROR",run.finishAfterCleanup().get("executionStatus"));
    }
    @Test void baselineIsIsolatedByExplicitMode() {
        assertFalse(RetrievalEvaluationJournal.Mode.AI_AUDITED_SYNTHETIC_EVALUATION.requiresLegacyBaseline());
        assertFalse(RetrievalEvaluationJournal.Mode.EXECUTOR_SYNTHETIC_SMOKE.requiresLegacyBaseline());
        assertTrue(RetrievalEvaluationJournal.Mode.DEVELOPMENT_CALIBRATION.requiresLegacyBaseline());
        assertTrue(RetrievalEvaluationJournal.Mode.HUMAN_APPROVED_INDEPENDENT_ACCEPTANCE.requiresLegacyBaseline());
    }
    @Test void actualProtocolTimeFieldsPersistBeforeAnyScoring() throws Exception {
        var claim=claimFirstRun("time","dataset","contract",directory); var run=journal(claim);
        var grant=new com.specagent.retrieval.protocol.RetrievalWire.Grant(UUID.randomUUID(),1,java.time.Instant.parse("2026-10-23T00:00:00Z"));
        run.queryStarted(1,"Q1",Map.of()); run.queryReturned(1,"Q1",11,grant,Map.of());
        String raw=Files.readString(run.artifactsDirectory().resolve("query-001-Q1.returned.json"));
        assertTrue(raw.contains("2026-10-23T00:00:00Z"));
        assertEquals(1,run.finishAfterCleanup().get("returnedQueries"));
    }
    @Test void receiptWriteFailureCannotLeaveATerminalPassReport() throws Exception {
        var claim=claimFirstRun("receipt-fault","dataset","contract",directory); var run=journal(claim);
        run.prepareQuality(Map.of("recallAt8",1.0),true); run.bodyFinished();
        recordOutcome(claim,"INTERRUPTED_OR_ERROR","preexisting terminal receipt must survive",directory);
        assertThrows(IllegalStateException.class,run::finishAfterCleanup);
        var result=RetrievalEvaluationSnapshot.parse(Files.readString(directory.resolve(claim.runId()+".result.json")));
        assertEquals("PENDING_TERMINAL_RECEIPT",result.path("verdict").asText());
        var receipt=RetrievalEvaluationSnapshot.parse(Files.readString(directory.resolve("dataset."+claim.runId()+".receipt.json")));
        assertEquals("INTERRUPTED_OR_ERROR",receipt.path("status").asText());
    }
    @Test void explicitLegacyEngineUsesLegacyDependenciesAndNeverCallsSharedPython() {
        var repository=org.mockito.Mockito.mock(com.specagent.retrieval.persistence.RetrievalEntryRepository.class);
        var authorization=org.mockito.Mockito.mock(com.specagent.retrieval.protocol.RetrievalGrantAuthorization.class);
        var shared=org.mockito.Mockito.mock(com.specagent.retrieval.protocol.SharedRetrievalHost.class);
        var store=org.mockito.Mockito.mock(com.specagent.retrieval.protocol.RetrievalStore.class);
        UUID project=UUID.randomUUID();
        var actor=new com.specagent.access.ActorContext(UUID.randomUUID(),UUID.randomUUID(),com.specagent.access.ActorSource.LOCAL);
        org.mockito.Mockito.when(authorization.issue(project)).thenReturn(new com.specagent.retrieval.protocol.RetrievalGrantAuthorization.Delegation(actor,null));
        var baseline=new com.specagent.retrieval.search.HybridRetriever(repository,
            new com.specagent.retrieval.search.VectorCandidateRetriever(new com.specagent.retrieval.embedding.NoopEmbeddingGateway(),repository),authorization);
        baseline.configureShared(shared,store,"java-hybrid.v1");
        var request=new com.specagent.retrieval.RetrievalQuery(project,null,null,"EXPLICIT_SEARCH","independent synthetic baseline",
            List.of(com.specagent.retrieval.RetrievalScope.PROJECT),8,8000,Set.of(),Set.of());
        assertTrue(baseline.retrieve(request).isEmpty());
        org.mockito.Mockito.verify(repository).lexical(project,request.queryText(),48,null,List.of());
        org.mockito.Mockito.verify(repository).trigram(project,request.queryText(),48,null,List.of());
        org.mockito.Mockito.verify(authorization).validate(org.mockito.ArgumentMatchers.eq(project),org.mockito.ArgumentMatchers.anyMap());
        org.mockito.Mockito.verifyNoInteractions(shared,store);
    }
}
