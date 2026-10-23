package com.specagent.retrieval;

import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.retrieval.protocol.*;
import com.specagent.retrieval.eval.RetrievalEvaluationJournal;
import com.specagent.retrieval.eval.RetrievalEvaluationJournal.Mode;
import com.specagent.retrieval.eval.RetrievalRecoveryGate;
import com.specagent.retrieval.protocol.RetrievalWire.Vector;
import com.specagent.workspace.project.*;
import com.specagent.workspace.node.NodeService;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spec.agent.retrieval.engine=python-rag.v1","spec.agent.retrieval.embedding.worker.enabled=false","spec.global-assistant.engine=langchain-ga.v1"})
@ActiveProfiles("test")
class SharedRetrievalIntegrationTest {
    @Autowired com.specagent.assistant.tool.GaCatalogProjection catalogs;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantApplicationService gaApplication;
    @Autowired com.specagent.assistant.conversation.GlobalAssistantConversationService gaConversations;
    @Autowired com.specagent.assistant.conversation.GlobalAssistantRunRepository gaRuns;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunEventService gaEvents;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunLifecycleService gaLifecycle;
    @org.springframework.boot.test.mock.mockito.MockBean com.specagent.modelsettings.OpenCodeSettingsRepository modelSettings;
    @org.springframework.boot.test.mock.mockito.MockBean com.specagent.model.provider.OpenCodeZenTransport modelTransport;
    @Autowired com.specagent.assistant.runtime.GaExecutionStore executions;
    @Autowired com.specagent.assistant.api.GaCapabilityBroker capabilityBroker;
    @Autowired com.specagent.assistant.conversation.ConversationDeleteService conversationDeletion;
    @Autowired com.specagent.workspace.context.ContextBuilder contexts;
    @Autowired com.specagent.agent.snapshot.AgentInputSnapshotBuilder snapshots;
    @Autowired com.specagent.retrieval.persistence.RetrievalEntryRepository entries;
    @Autowired RetrievalStore store;
    @Autowired RetrievalGrantAuthorization retrievalAuthorization;
    @Autowired RetrievalIndexJobs jobs;
    @Autowired RetrievalSourceJobs sourceJobs;
    @Autowired SharedRetrievalHost shared;
    @Autowired PythonRetrievalClient client;
    @Autowired ProjectService projects;
    @Autowired ProjectDeletionService deletion;
    @Autowired NodeService nodes;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentBrainProperties properties;
    @LocalServerPort int port;
    Project project,other;
    UUID node;
    Process python;
    Path log;
    String oldBase;

    com.specagent.retrieval.eval.RetrievalExecutionClaimManager.ExecutionClaim activeClaim;
    static final Path CLAIMS_DIR = Path.of("../data/retrieval-eval-claims");

    com.specagent.retrieval.eval.RetrievalEvaluationSnapshot evaluationSnapshot;

    RetrievalEvaluationJournal journal;
    Path runDirectory=CLAIMS_DIR;
    String smokeRaw;
    boolean smokeFault;
    RetrievalRecoveryGate.Qualified recovery;

    @BeforeEach void fixture(TestInfo test) throws Exception {
      try {
        if(test.getTestMethod().orElseThrow().getName().equals("prepareRecoveryAuthorizationManifest")) {
            Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_RAG_RECOVERY_PREPARE"))); return;
        }
        boolean smoke=test.getTestMethod().orElseThrow().getName().startsWith("syntheticExecutor");
        if(smoke) {
            Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_RAG_EXECUTOR_SMOKE")));
            assertNotEquals("true",System.getenv("SPEC_AGENT_RAG_EVALUATION"),"Never combine smoke with formal evaluation");
            smokeRaw=Files.readString(Path.of("src/test/resources/retrieval/executor-smoke.v1.json"));
            var synthetic=com.specagent.retrieval.eval.RetrievalEvaluationSnapshot.parse(smokeRaw);
            assertEquals("EXECUTOR_SYNTHETIC_SMOKE",synthetic.path("datasetRole").asText());
            assertEquals(3,synthetic.path("queries").size());
            runDirectory=Path.of("../scratch/logs/rag-d3-executor-repair/smoke-ledger");
            activeClaim=com.specagent.retrieval.eval.RetrievalExecutionClaimManager.claimFirstRun(
                "executor-smoke-"+UUID.randomUUID(),com.specagent.common.Hashes.sha256Hex(smokeRaw),
                com.specagent.common.Hashes.sha256Hex("POST_INTERRUPTION_EXECUTOR_REPAIR_SMOKE_V1"),runDirectory);
            journal=new RetrievalEvaluationJournal(activeClaim,runDirectory,Mode.EXECUTOR_SYNTHETIC_SMOKE,executionEnvironment());
        }
        if(test.getTestMethod().orElseThrow().getName().startsWith("syntheticRecovery")) {
            Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_RAG_RECOVERY_SMOKE")));
            assertNotEquals("true",System.getenv("SPEC_AGENT_RAG_EVALUATION"));
            smokeRaw=Files.readString(Path.of("src/test/resources/retrieval/executor-smoke.v1.json"));
            var environment=executionEnvironment();
            Path directory=Files.createTempDirectory(Path.of("../scratch/logs/rag-d3-recovery"),"synthetic-");
            Path manifest=RetrievalRecoveryGate.syntheticFixture(Path.of(".."),directory,smokeRaw,environment);
            recovery=RetrievalRecoveryGate.open(Path.of(".."),manifest,sha(manifest),environment,true);
            runDirectory=recovery.ledger(); activeClaim=recovery.claim();
            journal=new RetrievalEvaluationJournal(activeClaim,runDirectory,Mode.RECOVERY_RUN,environment);
        }
        if(test.getTestMethod().orElseThrow().getName().equals("realOllamaFiftyTwoReviewableQueriesEvaluateRecallNoAnswerStaleAndPermissionIsolation")
                && "true".equals(System.getenv("SPEC_AGENT_RAG_EVALUATION"))) {
            String file=System.getenv().getOrDefault("SPEC_AGENT_RAG_EVALUATION_DATASET","chinese-queries.v1.json");
            if(!file.equals("chinese-queries.v1.json")) {
              if("RECOVERY_RUN".equals(System.getenv("SPEC_AGENT_RAG_EVALUATION_MODE"))) {
                var environment=executionEnvironment();
                recovery=RetrievalRecoveryGate.open(Path.of(".."),Path.of(Objects.requireNonNull(System.getenv("SPEC_AGENT_RAG_RECOVERY_MANIFEST"))),
                    System.getenv("SPEC_AGENT_RAG_RECOVERY_AUTHORIZATION"),environment,false);
                evaluationSnapshot=recovery.snapshot();
                evaluationSnapshot.requireRuntime(Path.of(".."),System.getenv("SPEC_AGENT_GA_TEST_PYTHON"));
                activeClaim=recovery.claim(); runDirectory=recovery.ledger();
                journal=new RetrievalEvaluationJournal(activeClaim,runDirectory,Mode.RECOVERY_RUN,environment);
              } else {
                evaluationSnapshot=com.specagent.retrieval.eval.RetrievalEvaluationSnapshot.load(Path.of(".."),file,
                    System.getenv().getOrDefault("SPEC_AGENT_RAG_EVALUATION_MODE",
                        com.specagent.retrieval.eval.RetrievalEvaluationSnapshot.HUMAN_MODE));
                evaluationSnapshot.requireRunAuthorization(System.getenv("SPEC_AGENT_RAG_FIRST_RUN_AUTHORIZATION"));
                if(evaluationSnapshot.aiAudited()) evaluationSnapshot.requireRuntime(Path.of(".."),System.getenv("SPEC_AGENT_GA_TEST_PYTHON"));
                activeClaim=com.specagent.retrieval.eval.RetrievalExecutionClaimManager.claimFirstRun(
                    evaluationSnapshot.familyId(),evaluationSnapshot.datasetSha256(),evaluationSnapshot.freezeSha256(),CLAIMS_DIR);
                journal=new RetrievalEvaluationJournal(activeClaim,runDirectory,
                    evaluationSnapshot.aiAudited()?Mode.AI_AUDITED_SYNTHETIC_EVALUATION:Mode.HUMAN_APPROVED_INDEPENDENT_ACCEPTANCE,
                    executionEnvironment());
              }
            }
        }
        project=projects.createProject("shared-rag-fixture-"+UUID.randomUUID());
        other=projects.createProject("shared-rag-isolation-"+UUID.randomUUID());
        node=nodes.createRootNode(project.id(),project.activeRouteId(),"订单导出使用 CSV 文件，导出编号 EXP-2026-A。",null,List.of(),true).id();
        nodes.createRootNode(other.id(),other.activeRouteId(),"权限隔离秘密：另一个项目的退款账号。",null,List.of(),true);
        oldBase=properties.getBaseUrl();
        if(journal!=null) journal.stage("FIXTURES_CREATED",Map.of("projectId",project.id(),"otherProjectId",other.id()));
      } catch(Exception | AssertionError failure) { if(journal!=null) journal.failed(failure); throw failure; }
    }

    Map<String,Object> executionEnvironment() throws Exception {
        var identity=new LinkedHashMap<String,Object>();
        identity.put("database",jdbc.queryForMap("SELECT current_database() AS database,current_user AS connection_user,inet_server_addr()::text AS server_address,inet_server_port() AS server_port,version() AS version"));
        identity.put("jdbcUrl",Objects.requireNonNullElse(System.getenv("SPEC_AGENT_TEST_DB_URL"),"UNSET"));
        identity.put("pythonExecutable",Objects.requireNonNullElse(System.getenv("SPEC_AGENT_GA_TEST_PYTHON"),"UNSET"));
        identity.put("javaVersion",System.getProperty("java.version")); identity.put("profileId",PROFILE);
        identity.put("modelTag","qwen3-embedding:0.6b"); identity.put("dimensions",1024);
        identity.put("maxVectorDistance",0.50); identity.put("rrfK",60); identity.put("maxItems",8); identity.put("maxChars",8000);
        var hashes=new TreeMap<String,String>();
        for(String file:List.of("backend/src/test/java/com/specagent/retrieval/SharedRetrievalIntegrationTest.java",
                "backend/src/test/java/com/specagent/retrieval/eval/RetrievalEvaluationJournal.java",
                "backend/src/test/java/com/specagent/retrieval/eval/RetrievalExecutionClaimManager.java",
                "backend/src/test/java/com/specagent/retrieval/eval/RetrievalQualityScorer.java",
                "backend/src/test/resources/retrieval/executor-smoke.v1.json"))
            hashes.put(file,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("..").resolve(file)))));
        identity.put("executorCodeHashes",hashes);
        if("true".equals(System.getenv("SPEC_AGENT_RAG_RECOVERY_SMOKE")) || "true".equals(System.getenv("SPEC_AGENT_RAG_RECOVERY_PREPARE"))
                || "RECOVERY_RUN".equals(System.getenv("SPEC_AGENT_RAG_EVALUATION_MODE"))) {
            var docker=new ProcessBuilder("docker","inspect","--format","{{.Id}}|{{.Name}}|{{.Config.Image}}|{{json .NetworkSettings.Ports}}|{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}","spec-agent-iso-20261022-postgres").start();
            String container=new String(docker.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();
            assertEquals(0,docker.waitFor()); assertTrue(container.startsWith(RetrievalRecoveryGate.CONTAINER+"|/spec-agent-iso-20261022-postgres|pgvector/pgvector:pg17|"));
            assertTrue(container.contains("\"HostIp\":\"127.0.0.1\",\"HostPort\":\"55432\"")); assertTrue(container.endsWith("|172.24.0.2"));
            identity.put("containerId",RetrievalRecoveryGate.CONTAINER); identity.put("containerIdentity",container);
            assertEquals("http://127.0.0.1:11434",System.getenv("SPEC_AGENT_OLLAMA_URL"));
            identity.put("ollamaUrl",System.getenv("SPEC_AGENT_OLLAMA_URL"));
            try(var http=HttpClient.newHttpClient()) {
                var tags=JSON.readTree(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/tags")).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
                var models=new ArrayList<com.fasterxml.jackson.databind.JsonNode>(); tags.path("models").forEach(m->{ if(m.path("name").asText().equals("qwen3-embedding:0.6b")) models.add(m); });
                assertEquals(1,models.size()); identity.put("actualModelDigest",models.getFirst().path("digest").asText());
                var show=JSON.readTree(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/show")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"qwen3-embedding:0.6b\"}")).build(),HttpResponse.BodyHandlers.ofString()).body());
                identity.put("actualDimensions",show.path("model_info").path("qwen3.embedding_length").asInt());
            }
        }
        return identity;
    }
    static String sha(Path path) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    @FunctionalInterface interface CleanupAction { void run() throws Exception; }
    void cleanupStep(List<Throwable> errors,CleanupAction action) {
        try { action.run(); } catch(Exception | AssertionError failure) { errors.add(failure); if(journal!=null) journal.failed(failure); }
    }
    @AfterEach void cleanup() throws Exception {
        var errors=new ArrayList<Throwable>();
        cleanupStep(errors,()->{ if(journal!=null) journal.stage("CLEANUP_STARTED",Map.of()); });
        cleanupStep(errors,this::stopCandidatePython);
        cleanupStep(errors,()->{ if(properties!=null && oldBase!=null) properties.setBaseUrl(oldBase); });
        // Independent steps also clean a partially initialized fixture; one failure cannot skip the other project.
        for(var item:new Project[]{project,other}) if(item!=null) {
            cleanupStep(errors,()->jdbc.update("DELETE FROM retrieval_scope_grants WHERE corpus_id=?",item.id()));
            cleanupStep(errors,()->jdbc.update("DELETE FROM retrieval_index_jobs WHERE corpus_id=?",item.id()));
            cleanupStep(errors,()->jdbc.update("DELETE FROM retrieval_index_heads WHERE corpus_id=?",item.id()));
            cleanupStep(errors,()->jdbc.update("DELETE FROM retrieval_index_generations WHERE corpus_id=?",item.id()));
            cleanupStep(errors,()->deletion.deleteProject(item.id()));
        }
        cleanupStep(errors,()->{ if(journal!=null) journal.stage("CLEANUP_FINISHED",Map.of("errorCount",errors.size())); });
        Map<String,Object> finalReport=null;
        if(journal!=null) finalReport=journal.finishAfterCleanup();
        else if(activeClaim!=null) {
            // Journal/manifest initialization itself failed: preserve the consumed claim and an honest terminal receipt.
            com.specagent.retrieval.eval.RetrievalExecutionClaimManager.writeResult(activeClaim,
                write(Map.of("runId",activeClaim.runId(),"verdict","INTERRUPTED_OR_ERROR","failureReason","Journal initialization failed; no evaluation body entered")),runDirectory);
            com.specagent.retrieval.eval.RetrievalExecutionClaimManager.recordOutcome(activeClaim,"INTERRUPTED_OR_ERROR","Journal initialization failed",runDirectory);
        }
        if(!errors.isEmpty()) { var failure=new Exception("Evaluation cleanup failed"); errors.forEach(failure::addSuppressed); throw failure; }
        if(smokeFault) {
            assertNotNull(finalReport); assertEquals("INTERRUPTED_OR_ERROR",finalReport.get("executionStatus"));
            assertEquals(2,finalReport.get("startedQueries")); assertEquals(1,finalReport.get("returnedQueries"));
            assertTrue(Files.exists(journal.artifactsDirectory().resolve("query-001-SM001.returned.json")));
            byte[] before=Files.readAllBytes(runDirectory.resolve(activeClaim.runId()+".result.json"));
            assertThrows(java.io.UncheckedIOException.class,()->journal.finishAfterCleanup());
            assertArrayEquals(before,Files.readAllBytes(runDirectory.resolve(activeClaim.runId()+".result.json")));
            assertThrows(IllegalStateException.class,()->com.specagent.retrieval.eval.RetrievalExecutionClaimManager.recordOutcome(activeClaim,"COMPLETED_PASS","overwrite",runDirectory));
        } else if(finalReport!=null) assertEquals("PASS",finalReport.get("verdict"),()->"Execution report: "+runDirectory.resolve(activeClaim.runId()+".result.json"));
    }
    void stopCandidatePython() throws Exception {
        if(python!=null) {
            var children=python.descendants().toList(); children.forEach(ProcessHandle::destroyForcibly);
            python.destroyForcibly();
            if(!python.waitFor(5,TimeUnit.SECONDS)) throw new IllegalStateException("Python process did not terminate during cleanup");
            for(var child:children) if(child.isAlive()) child.onExit().get(5,TimeUnit.SECONDS);
            python=null;
        }
        if(log!=null && journal==null) for(int attempt=0;attempt<30;attempt++) {
            try { Files.deleteIfExists(log); break; }
            catch(java.nio.file.FileSystemException ex) { if(attempt==29) throw ex; Thread.sleep(100); }
        }
        log=null;
    }

    @Test void candidateRuntimeColdRepeatedStartsAndSustainedServiceUseActualHostRpc() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_RUNTIME_QUALIFICATION")));
        assertNotNull(System.getenv("SPEC_AGENT_GA_TEST_PYTHON"),"Select the candidate explicitly, never replace original .venv");
        var report=new LinkedHashMap<String,Object>(); var starts=new ArrayList<Map<String,Object>>();
        report.put("recordedAt",Instant.now().toString()); report.put("boundary","fresh Python processes + actual Java RPC/test DB/pgvector/local Ollama; no live chat calls");
        report.put("coldScope","cold interpreter/module state, not OS disk cache or Ollama model eviction");
        report.put("retries",0); report.put("plannedStarts",6); report.put("starts",starts);
        report.put("productionRuntimeChanged",false); report.put("originalEnvironmentReplaced",false);
        var request=new RetrievalQuery(project.id(),null,null,"EXPLICIT_SEARCH","EXP-2026-A",
            List.of(RetrievalScope.PROJECT),8,8000,Set.of(),Set.of());
        try {
            for(int index=0;index<6;index++) {
                long begin=System.nanoTime(); startPython();
                var startup=new LinkedHashMap<String,Object>(); startup.put("ordinal",index+1);
                startup.put("startupMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)); startup.put("pid",python.pid());
                try(var http=HttpClient.newHttpClient()) {
                    var endpoint=URI.create(properties.getBaseUrl()+"/internal/v1/retrieval/health");
                    var denied=http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.discarding());
                    assertEquals(401,denied.statusCode());
                    var health=http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                        .header("X-Spec-Agent-Internal-Token",properties.getInternalSecret()).GET().build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(200,health.statusCode()); var value=JSON.readTree(health.body());
                    startup.put("health",value);
                    assertTrue(value.path("storeReady").asBoolean(),health.body()); assertTrue(value.path("ollamaReady").asBoolean(),health.body());
                }
                if(index==0) { UUID generation=store.ensureProjectGeneration(project.id()); assertTrue(shared.indexOneBatch(project.id(),generation)); }
                var result=shared.search(request,UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());
                assertFalse(result.vectorUnavailable()); assertTrue(result.items().stream().anyMatch(i->i.source().sourceRef().equals("node:"+node)));
                startup.put("status","PASS"); startup.put("actualHybridQuery",true); starts.add(startup);
                if(index<5) stopCandidatePython();
            }
            long begin=System.nanoTime(); int samples=0;
            do {
                assertTrue(python.isAlive(),"Candidate service exited during sustained run; no restart permitted");
                var result=shared.search(request,UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());
                assertFalse(result.vectorUnavailable()); assertFalse(result.items().isEmpty()); samples++;
                Thread.sleep(5000);
            } while(System.nanoTime()-begin<TimeUnit.SECONDS.toNanos(60));
            report.put("sustainedServiceMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin));
            report.put("sustainedQuerySamples",samples); report.put("status","PASS");
        } catch(Exception | AssertionError failure) {
            report.put("status","FAILED"); report.put("failureType",failure.getClass().getSimpleName());
            if(log!=null) report.put("startupDiagnostics",Files.readString(log).replace(properties.getInternalSecret(),"[REDACTED]"));
            throw failure;
        } finally {
            Files.writeString(Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_CANDIDATE_RUNTIME_STARTUP.json"),write(report));
        }
    }
    RetrievalQuery query(String query) {
        return new RetrievalQuery(project.id(),project.activeRouteId(),node,"EXPLICIT_SEARCH",query,
                List.of(RetrievalScope.PROJECT,RetrievalScope.ROUTE),8,8000,Set.of("node:"+node),Set.of());
    }
    Candidates candidate(Search request,List<String> lanes,Vector vector) {
        return new Candidates(request.protocolVersion(),request.requestId(),request.workload(),request.scopeGrant(),request.profileId(),
                request.indexGeneration(),request.deadline(),request.query(),lanes,48,vector);
    }
    Vector normalized() {
        double[] values=new double[1024]; values[0]=1;
        return new Vector(1024,values,RetrievalVectors.checksum(values));
    }
    IndexResult result(IndexBatch batch) {
        return new IndexResult(batch.protocolVersion(),batch.requestId(),batch.workload(),batch.scopeGrant(),batch.profileId(),
                batch.indexGeneration(),batch.deadline(),batch.jobId(),batch.leaseId(),batch.expectedVersion(),batch.sources().stream()
                .map(s->new IndexVector(s.entryId(),s.sourceRef(),s.sourceVersion(),s.contentHash(),s.location(),normalized())).toList());
    }

    @Test void excludedResourcesAreMarkedWithoutRetainingRawTextAndDoNotStarveBackfill() {
        var blank=nodes.createFloatingWorkspaceNode(project.id(),com.specagent.workspace.node.NodeKind.RESOURCE,"TEXT",
            Map.of("text","   "),com.specagent.workspace.node.NodeAuthorKind.USER,null);
        var oversized=nodes.createFloatingWorkspaceNode(project.id(),com.specagent.workspace.node.NodeKind.RESOURCE,"TEXT",
            Map.of("text","x".repeat(200001)),com.specagent.workspace.node.NodeAuthorKind.USER,null);
        for(var item:List.of(blank,oversized)) {
            var row=jdbc.queryForMap("SELECT state,raw_text,metadata::text FROM retrieval_source_projections WHERE node_id=?",item.id());
            assertEquals("SKIPPED",row.get("state")); assertEquals("",row.get("raw_text")); assertEquals("{}",row.get("metadata"));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE source_id=?",Integer.class,item.id()));
        }
        assertTrue(sourceJobs.missingResource(project.id()).isEmpty());
    }

    @Test void scopedCandidatesSourceVersionRevocationAndUnknownIdentityFailClosed() {
        var request=store.issueProject(query("EXP-2026-A"),UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());
        var lanes=store.candidates(candidate(request,List.of("project-lexical"),null));
        assertFalse(lanes.lanes().getFirst().entries().isEmpty());
        var source=lanes.lanes().getFirst().entries().getFirst(); assertEquals(project.id(),source.projectId());
        assertEquals("node:"+node,source.sourceRef());
        var validation=new Validation(request.protocolVersion(),request.requestId(),request.workload(),request.scopeGrant(),request.profileId(),
                request.indexGeneration(),request.deadline(),List.of(source));
        assertEquals(List.of(source.entryId()),store.validateSources(validation).allowedEntryIds());
        jdbc.update("UPDATE retrieval_entries SET metadata=metadata||'{\"updatedFixture\":true}'::jsonb WHERE id=?",source.entryId());
        assertEquals(List.of(source.entryId()),store.validateSources(validation).rejectedEntryIds());
        jdbc.update("UPDATE retrieval_scope_grants SET revoked=TRUE WHERE id=?",request.scopeGrant().grantId());
        assertThrows(IllegalStateException.class,()->store.candidates(candidate(request,List.of("project-lexical"),null)));
    }

    @Test void indexLeaseSourceCasReplayAndAtomicGenerationActivation() {
        UUID active=store.ensureProjectGeneration(project.id());
        var first=jobs.claim(project.id(),active).orElseThrow(); var firstResult=result(first);
        assertTrue(jobs.commit(firstResult)); assertFalse(jobs.commit(firstResult));
        UUID generation=jobs.prepareGeneration(project.id());
        var request=store.issueProject(query("EXP-2026-A"),UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());
        var batch=jobs.claim(project.id(),generation).orElseThrow();
        assertTrue(jobs.commit(result(batch)));
        assertEquals(active,jdbc.queryForObject("SELECT active_generation FROM retrieval_index_heads WHERE corpus_id=?",UUID.class,project.id()));
        jobs.activate(project.id(),generation,0);
        assertEquals(generation,jdbc.queryForObject("SELECT active_generation FROM retrieval_index_heads WHERE corpus_id=?",UUID.class,project.id()));
        assertThrows(IllegalStateException.class,()->store.candidates(candidate(request,List.of("vector"),normalized())));
        UUID another=jobs.prepareGeneration(project.id());
        var stale=jobs.claim(project.id(),another).orElseThrow();
        jdbc.update("UPDATE retrieval_entries SET metadata=metadata||'{\"casFixture\":true}'::jsonb WHERE id=?",stale.sources().getFirst().entryId());
        assertThrows(IllegalStateException.class,()->jobs.commit(result(stale)));
        assertThrows(IllegalStateException.class,()->jobs.activate(project.id(),another,1));
        assertEquals(generation,jdbc.queryForObject("SELECT active_generation FROM retrieval_index_heads WHERE corpus_id=?",UUID.class,project.id()));
    }

    @Test void staleAuthorityCannotPromoteUserTextToConfirmedEvidence() {
        var request=store.issueProject(query("EXP-2026-A"),UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());
        var original=store.candidates(candidate(request,List.of("project-lexical"),null)).lanes().getFirst().entries().getFirst();
        jdbc.update("UPDATE retrieval_entries SET authority='CONFIRMED' WHERE id=?",original.entryId());
        assertTrue(store.candidates(candidate(request,List.of("project-lexical"),null)).lanes().getFirst().entries().isEmpty());
        var validation=new Validation(request.protocolVersion(),request.requestId(),request.workload(),request.scopeGrant(),request.profileId(),request.indexGeneration(),request.deadline(),List.of(original));
        assertEquals(List.of(original.entryId()),store.validateSources(validation).rejectedEntryIds());
    }

    @Test void expiredLeaseCannotCommitAndInvalidDerivedRowsDoNotBlockLaterIndexing() {
        UUID generation=store.ensureProjectGeneration(project.id());
        var expired=jobs.claim(project.id(),generation).orElseThrow();
        assertTrue(jobs.claim(project.id(),generation).isEmpty());
        jdbc.update("UPDATE retrieval_index_jobs SET deadline=now()-interval '1 second' WHERE id=?",expired.jobId());
        var replacement=jobs.claim(project.id(),generation).orElseThrow();
        assertThrows(IllegalStateException.class,()->jobs.commit(result(expired)));
        assertTrue(jobs.commit(result(replacement)));
        // Unsafe/orphaned derived rows are retired without changing any canonical facts.
        for(int i=0;i<16;i++) jdbc.update("INSERT INTO retrieval_entries(id,project_id,corpus_id,source_kind,source_id,source_ref,scope,authority,content,content_hash) VALUES(?,?,?,'NODE',?,?,'PROJECT','USER_AUTHORED','orphan',?)",
            UUID.randomUUID(),project.id(),project.id(),UUID.randomUUID(),"a-invalid-"+i,com.specagent.common.Hashes.sha256Hex("orphan"));
        var later=nodes.createFloatingWorkspaceNode(project.id(),com.specagent.workspace.node.NodeKind.KNOWLEDGE,"NOTE",Map.of("text","later index candidate"),com.specagent.workspace.node.NodeAuthorKind.USER,null);
        assertTrue(jobs.claim(project.id(),generation).isEmpty());
        assertEquals(16,jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE project_id=? AND source_ref LIKE 'a-invalid-%' AND retracted_at IS NOT NULL",Integer.class,project.id()));
        var next=jobs.claim(project.id(),generation).orElseThrow();
        assertTrue(next.sources().stream().anyMatch(item->item.sourceRef().equals("node:"+later.id())));
        assertTrue(jobs.commit(result(next)));
        assertNotNull(jdbc.queryForObject("SELECT content::text FROM nodes WHERE id=?",String.class,later.id()));
    }

    @Test void realPythonOllamaIndexAndChineseParaphraseUseExistingPgvectorThroughHostRpc() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_RAG_LIVE_TEST")));
        startPython();
        String raw="  # 导出资源 😀\r\n\r\n订单导出 API 的精确路径是 /exports/EXP-2026-A。\r\n\r\n"+"保留资源引用及原始位置。\n".repeat(150);
        var resource=nodes.createFloatingWorkspaceNode(project.id(),com.specagent.workspace.node.NodeKind.RESOURCE,"TEXT",
                Map.of("text",raw),com.specagent.workspace.node.NodeAuthorKind.USER,null);
        UUID generation=store.ensureProjectGeneration(project.id());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE project_id=? AND source_kind='RESOURCE_CHUNK'",Integer.class,project.id()));
        try { assertTrue(shared.splitOneSource(project.id(),generation)); }
        catch(RuntimeException ex) { fail("Source RPC failed: "+Files.readString(log),ex); }
        assertFalse(shared.splitOneSource(project.id(),generation));
        var chunks=jdbc.queryForList("SELECT content,content_hash,metadata->>'startOffset' AS start,metadata->>'endOffset' AS end FROM retrieval_entries WHERE source_id=? ORDER BY (metadata->>'chunkIndex')::int",resource.id());
        assertTrue(chunks.size()>1); StringBuilder reassembled=new StringBuilder();
        for(var chunk:chunks) {
            int start=Integer.parseInt(chunk.get("start").toString()),end=Integer.parseInt(chunk.get("end").toString());
            assertEquals(raw.substring(start,end),chunk.get("content")); reassembled.append(chunk.get("content"));
        }
        assertEquals(raw,reassembled.toString());
        assertTrue(shared.indexOneBatch(project.id(),generation));
        assertFalse(shared.indexOneBatch(project.id(),generation));
        var resourceQuery=new RetrievalQuery(project.id(),project.activeRouteId(),node,"EXPLICIT_SEARCH","/exports/EXP-2026-A",
                List.of(RetrievalScope.RESOURCE),8,8000,Set.of(),Set.of());
        var resourceResult=shared.search(resourceQuery,UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());
        assertTrue(resourceResult.items().stream().anyMatch(i->i.source().sourceRef().startsWith("resource-chunk:"+resource.id()+":")));
        assertTrue(resourceResult.items().stream().allMatch(i->i.source().location().startOffset()!=null));
        long start=System.nanoTime();
        var result=shared.search(query("把订单下载成表格用哪种格式？"),UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());
        assertFalse(result.vectorUnavailable());
        assertTrue(result.items().stream().anyMatch(item->item.source().sourceRef().equals("node:"+node)),write(result));
        assertTrue(result.items().stream().allMatch(item->item.source().projectId().equals(project.id())));
        assertTrue(result.items().stream().anyMatch(item->item.lanes().contains("vector")));
        var snapshot=contexts.buildForNodeQuery(project.id(),project.activeRouteId(),node,"/exports/EXP-2026-A");
        var frozen=snapshots.build(snapshot);
        assertEquals("python-rag.v1",frozen.metadata().retrieval().retrievalEngineVersion());
        assertFalse(frozen.metadata().retrieval().supplementalRetrievalUnavailable());
        assertFalse(frozen.metadata().retrieval().vectorUnavailable());
        assertTrue(frozen.lineage().stream().anyMatch(i->i.node().id().equals(node)));
        assertTrue(frozen.retrievedContext().stream().anyMatch(i->i.sourceRef().startsWith("resource-chunk:"+resource.id()+":")));
        long grants=jdbc.queryForObject("SELECT count(*) FROM retrieval_scope_grants WHERE corpus_id=?",Long.class,project.id());
        String liveBase=properties.getBaseUrl();
        properties.setBaseUrl("http://127.0.0.1:1");
        assertEquals(com.specagent.agent.protocol.AgentContracts.write(frozen),com.specagent.agent.protocol.AgentContracts.write(snapshots.build(snapshot)));
        assertEquals(grants,jdbc.queryForObject("SELECT count(*) FROM retrieval_scope_grants WHERE corpus_id=?",Long.class,project.id()));
        var unavailableSnapshot=contexts.buildForNodeQuery(project.id(),project.activeRouteId(),node,"补充服务断开后保留本轮必需上下文");
        var unavailable=snapshots.build(unavailableSnapshot);
        assertTrue(unavailable.metadata().retrieval().supplementalRetrievalUnavailable());
        assertTrue(unavailable.lineage().stream().anyMatch(i->i.node().id().equals(node)));
        properties.setBaseUrl(liveBase);
        assertEquals(com.specagent.agent.protocol.AgentContracts.write(unavailable),com.specagent.agent.protocol.AgentContracts.write(snapshots.build(unavailableSnapshot)));
        var evidence=Map.of("recordedAt",Instant.now().toString(),"boundary","actual Python + local Ollama + authenticated Java RPC + existing pgvector; isolated test database",
                "profileId",PROFILE,"model","qwen3-embedding:0.6b","dimensions",1024,"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),
                "query",result.items().stream().map(item->item.source().sourceRef()).toList(),"productionEngineChanged",false);
        Files.writeString(Path.of("../docs/v2/evidence/SHARED_RAG_U1_REAL_INTEGRATION.json"),write(evidence));
    }

    @Test void actualUnreachableOllamaKeepsSharedLexicalButFailsSemanticAndIndexExplicitly() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_RAG_LIVE_TEST")));
        startPython("http://127.0.0.1:1");
        var lexical=shared.search(query("EXP-2026-A"),UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());
        assertTrue(lexical.vectorUnavailable()); assertEquals(List.of("VECTOR_UNAVAILABLE"),lexical.warnings()); assertFalse(lexical.items().isEmpty());
        var original=store.issueProject(query("EXP-2026-A"),UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of());
        var semantic=new Search(original.protocolVersion(),original.requestId(),original.workload(),original.scopeGrant(),original.profileId(),original.indexGeneration(),original.deadline(),original.retrievalEngineVersion(),original.query(),"SEMANTIC_ONLY",original.limits());
        assertEquals("OLLAMA_UNAVAILABLE",assertThrows(IllegalStateException.class,()->client.search(semantic)).getMessage());
        UUID generation=store.ensureProjectGeneration(project.id());
        assertEquals("OLLAMA_UNAVAILABLE",assertThrows(IllegalStateException.class,()->shared.indexOneBatch(project.id(),generation)).getMessage());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM retrieval_index_jobs WHERE corpus_id=? AND state='FAILED'",Integer.class,project.id()));
        Files.writeString(Path.of("../docs/v2/evidence/SHARED_RAG_REAL_OLLAMA_UNAVAILABLE.json"),write(Map.of("recordedAt",Instant.now().toString(),"status","PASS","boundary","actual isolated Python instance pointed to unreachable loopback Ollama origin; real authorized Java SQL/RPC and test DB; existing Ollama service untouched","lexicalAvailable",true,"semanticAndIndexError","OLLAMA_UNAVAILABLE","productionEngineChanged",false)));
    }

    @Test void realOllamaFiftyTwoReviewableQueriesEvaluateRecallNoAnswerStaleAndPermissionIsolation() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_RAG_EVALUATION")));
        String datasetFile=System.getenv().getOrDefault("SPEC_AGENT_RAG_EVALUATION_DATASET","chinese-queries.v1.json");
        Path datasetPath=Path.of("../contracts/retrieval/evaluation/"+datasetFile);
        boolean acceptance=!datasetFile.equals("chinese-queries.v1.json");
        String rawJson=acceptance?evaluationSnapshot.datasetRaw():Files.readString(datasetPath);
        executeEvaluation(rawJson,datasetFile,acceptance,false);
    }

    @Test void syntheticExecutorCompletesRealThreeQueryLifecycle() throws Exception {
        executeEvaluation(smokeRaw,"executor-smoke.v1.json",true,true);
    }
    @Test void syntheticRecoveryCompletesRealThreeQueryLifecycle() throws Exception {
        executeEvaluation(smokeRaw,"executor-smoke.v1.json",true,true);
    }
    @Test void syntheticRecoveryPreservesFirstReturnWhenSecondQueryCrashes() throws Exception {
        smokeFault=true;
        assertEquals("INJECTED_SECOND_QUERY_CRASH",assertThrows(IllegalStateException.class,()->executeEvaluation(smokeRaw,"executor-smoke.v1.json",true,true)).getMessage());
    }
    @Test void prepareRecoveryAuthorizationManifest() throws Exception {
        var root=Path.of("..").toAbsolutePath().normalize();
        var parent=JSON.readValue(Files.readString(CLAIMS_DIR.resolve("rag-d3-v2-68.claim.json")),com.specagent.retrieval.eval.RetrievalExecutionClaimManager.ExecutionClaim.class);
        Path data=root.resolve("contracts/retrieval/evaluation"),directory=root.resolve("data/retrieval-eval-recovery");
        Path manifest=directory.resolve("authorization.v1.json"); var environment=executionEnvironment();
        var value=RetrievalRecoveryGate.manifest(root,data.resolve("acceptance-candidate.v2.3.json"),data.resolve("acceptance-freeze.v2.3.json"),
            data.resolve("ai-review-decisions.v1.json"),parent,CLAIMS_DIR,directory.resolve("executor-byte-diff.v1.json"),environment,false);
        RetrievalRecoveryGate.writeImmutable(manifest,value);
        var qualified=RetrievalRecoveryGate.open(root,manifest,sha(manifest),environment,false);
        qualified.snapshot().requireRuntime(root,System.getenv("SPEC_AGENT_GA_TEST_PYTHON"));
        System.out.println("RECOVERY_MANIFEST_VALIDATED "+manifest+" SHA256="+sha(manifest));
    }

    @Test void syntheticExecutorPreservesFirstReturnWhenSecondQueryCrashes() throws Exception {
        smokeFault=true;
        var failure=assertThrows(IllegalStateException.class,()->executeEvaluation(smokeRaw,"executor-smoke.v1.json",true,true));
        assertEquals("INJECTED_SECOND_QUERY_CRASH",failure.getMessage());
    }

    void executeEvaluation(String rawJson,String datasetFile,boolean acceptance,boolean synthetic) throws Exception {
      try {
        var dataset=com.specagent.retrieval.eval.RetrievalEvaluationSnapshot.parse(rawJson);
        String reviewStatus=dataset.path("humanReviewStatus").asText();
        boolean ai=synthetic || (acceptance && evaluationSnapshot.aiAudited());
        if(journal!=null) journal.stage("PYTHON_STARTING",Map.of());
        startPython();
        if(journal!=null) journal.stage("PYTHON_READY",Map.of("pid",python.pid()));
        try(var resources=new TestResourceSampler(python)) {
        Map<UUID,String> aliases=new HashMap<>(); Set<UUID> stale=new HashSet<>();
        for(var source:dataset.path("sources")) {
            String kind=source.path("kind").asText(); UUID owner=kind.equals("PRIVATE_NODE")?other.id():project.id();
            var item=nodes.createFloatingWorkspaceNode(owner,kind.equals("RESOURCE")?com.specagent.workspace.node.NodeKind.RESOURCE:com.specagent.workspace.node.NodeKind.KNOWLEDGE,
                kind.equals("RESOURCE")?"TEXT":"NOTE",Map.of("text",source.path("text").asText()),com.specagent.workspace.node.NodeAuthorKind.USER,null);
            aliases.put(item.id(),source.path("alias").asText()); if(kind.equals("STALE_NODE")) stale.add(item.id());
        }
        for(var corpus:List.of(project,other)) {
            UUID generation=store.ensureProjectGeneration(corpus.id());
            int sources=0; while(shared.splitOneSource(corpus.id(),generation)) assertTrue(++sources<=4);
            int batches=0; while(shared.indexOneBatch(corpus.id(),generation)) assertTrue(++batches<=8);
        }
        // Safety probes must have actual vectors before retraction/isolation.
        for(var source:aliases.entrySet()) if(stale.contains(source.getKey()) || source.getValue().startsWith("P")) {
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE source_id=? AND embedding IS NOT NULL AND retracted_at IS NULL",
                Integer.class,source.getKey())>0,"Safety fixture was never indexed: "+source.getValue());
        }
        // Intentionally leave the derived projection untouched: final canonical checks must still reject it.
        for(UUID item:stale) jdbc.update("UPDATE nodes SET retracted_at=now() WHERE id=?",item);
        if(journal!=null) journal.stage("INDEX_READY",Map.of("fixtureAliases",aliases));
        var mode=recovery!=null?Mode.RECOVERY_RUN:synthetic?Mode.EXECUTOR_SYNTHETIC_SMOKE:ai?Mode.AI_AUDITED_SYNTHETIC_EVALUATION:acceptance?Mode.HUMAN_APPROVED_INDEPENDENT_ACCEPTANCE:Mode.DEVELOPMENT_CALIBRATION;
        com.specagent.retrieval.search.HybridRetriever baseline=null;
        if(mode.requiresLegacyBaseline()) {
          baseline=new com.specagent.retrieval.search.HybridRetriever(entries,
            new com.specagent.retrieval.search.VectorCandidateRetriever(new com.specagent.retrieval.embedding.NoopEmbeddingGateway(),entries),retrievalAuthorization);
          baseline.configureShared(shared,store,"java-hybrid.v1");
          assertFalse(baseline.usesSharedPython());
        }
        List<Map<String,Object>> observations=new ArrayList<>(); List<Long> timings=new ArrayList<>();
        double recall=0,mrr=0,baseRecall=0; int labelled=0,empty=0,correctEmpty=0,invalidSources=0,leaks=0; long heapPeak=0;
        Set<String> excluded=Set.of("node:"+node);
        int ordinal=0;
        for(var labelledQuery:dataset.path("queries")) {
            ordinal++; String queryId=labelledQuery.path("id").asText();
            if(journal!=null) journal.queryStarted(ordinal,queryId,labelledQuery);
            if(smokeFault && ordinal==2) throw new IllegalStateException("INJECTED_SECOND_QUERY_CRASH");
            String category=labelledQuery.path("category").asText(),query=labelledQuery.path("query").asText();
            List<String> expected=new ArrayList<>(),forbidden=new ArrayList<>();
            labelledQuery.path("expectedAliases").forEach(a->expected.add(a.asText()));
            labelledQuery.path("forbiddenAliases").forEach(a->forbidden.add(a.asText()));
            List<RetrievalScope> scopes=new ArrayList<>();
            labelledQuery.path("scopes").forEach(value->scopes.add(RetrievalScope.valueOf(value.asText())));
            if(scopes.isEmpty()) scopes.add(category.equals("RESOURCE_CONTENT")?RetrievalScope.RESOURCE:RetrievalScope.PROJECT);
            var request=new RetrievalQuery(project.id(),null,null,"EXPLICIT_SEARCH",query,scopes,8,8000,Set.of(),Set.of());
            long begin=System.nanoTime(); var result=shared.search(request,UUID.randomUUID(),"CONTEXT_PROJECTION",excluded);
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin);
            if(journal!=null) journal.queryReturned(ordinal,queryId,elapsed,result,aliases);
            timings.add(elapsed);
            List<String> rawRetrieved=result.items().stream()
                .map(i->aliases.getOrDefault(UUID.fromString(i.source().sourceRef().split(":")[1]),"UNLABELLED"))
                .toList();
            List<String> actual=rawRetrieved.stream().distinct().toList();
            List<String> legacy=baseline==null?List.of():baseline.retrieve(request).stream().filter(c->!excluded.contains(c.entry().sourceRef())).limit(8)
                .map(c->aliases.getOrDefault(c.entry().sourceId(),"UNLABELLED")).distinct().toList();
            int queryInvalidSources=0;
            for(var item:result.items()) {
                if(!project.id().equals(item.source().projectId()) || forbidden.contains(aliases.get(UUID.fromString(item.source().sourceRef().split(":")[1])))) leaks++;
                var source=item.source();
                var rows=jdbc.queryForList("SELECT * FROM retrieval_entries WHERE id=?",source.entryId());
                if(stale.contains(UUID.fromString(source.sourceRef().split(":")[1])) || rows.size()!=1
                        || !project.id().equals(source.projectId()) || !project.id().equals(source.corpusId())
                        || !aliases.containsKey(UUID.fromString(source.sourceRef().split(":")[1]))
                        || !scopes.stream().map(Enum::name).toList().contains(source.scope())
                        || !store.canonicalCurrent(rows.getFirst())
                        || !source.sourceVersion().equals(rows.getFirst().get("source_version"))
                        || !source.contentHash().equals(rows.getFirst().get("content_hash"))
                        || !source.sourceRef().equals(rows.getFirst().get("source_ref"))
                        || !source.authority().equals(rows.getFirst().get("authority"))) { invalidSources++; queryInvalidSources++; }
            }
            if(!expected.isEmpty()) {
                labelled++; recall+=(double)expected.stream().filter(actual::contains).count()/expected.size();
                baseRecall+=(double)expected.stream().filter(legacy::contains).count()/expected.size();
                for(int rank=0;rank<rawRetrieved.size();rank++) if(expected.contains(rawRetrieved.get(rank))) { mrr+=1.0/(rank+1); break; }
            } else { empty++; if(actual.isEmpty()) correctEmpty++; }
            heapPeak=Math.max(heapPeak,java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            var observation=new LinkedHashMap<String,Object>();
            observation.put("id",labelledQuery.path("id").asText()); observation.put("category",category); observation.put("query",query);
            if(recovery!=null && !synthetic) observation.put("formalObservationOrdinal",queryId.equals("B001")?2:1);
            observation.put("expectedAliases",expected); observation.put("forbiddenAliases",forbidden);
            List<String> background=new ArrayList<>(); labelledQuery.path("backgroundAliases").forEach(a->background.add(a.asText()));
            observation.put("backgroundAliases",background);
            observation.put("invalidSourceCount",queryInvalidSources);
            observation.put("retrievedAliases",rawRetrieved); observation.put("distinctRetrievedAliases",actual);
            observation.put("legacyLexicalAliases",legacy);
            observation.put("elapsedMs",elapsed); observation.put("vectorUnavailable",result.vectorUnavailable()); observation.put("labelReview",labelledQuery.path("labelReview").asText());
            observation.put("sources",result.items().stream().map(Ranked::source).toList()); observations.add(observation);
            if(journal!=null) journal.queryScored(ordinal,queryId,observation);
        }
        Collections.sort(timings); var report=new LinkedHashMap<String,Object>();
        report.put("recordedAt",Instant.now().toString()); report.put("boundary","actual Python + local qwen3-embedding:0.6b + authenticated Java RPC + existing pgvector; isolated canonical test sources");
        report.put("authorship",dataset.path("authorship").asText()); report.put("humanReviewStatus",reviewStatus); report.put("queryCount",observations.size());
        report.put("datasetFile",datasetFile); report.put("datasetRole",dataset.path("datasetRole").asText());
        report.put("datasetSha256",com.specagent.common.Hashes.sha256Hex(rawJson));
        report.put("profileId",PROFILE);
        report.put("legacyBaselineEnabled",mode.requiresLegacyBaseline());
        if(mode.requiresLegacyBaseline()) report.put("javaLegacyLexicalRecallAt8",baseRecall/labelled);
        report.put("p50Ms",timings.get(timings.size()/2)); report.put("p95Ms",timings.get((int)Math.ceil(timings.size()*0.95)-1));
        report.put("javaHeapObservedPeakBytes",heapPeak); report.put("resourceMeasurementLimit","Sampled observations; Java heap after each query, Python working set and whole GPU device shared with other apps"); report.put("resourceSamples",resources.report());
        report.put("maxVectorDistance",0.50); report.put("evaluationUse",synthetic?"post-interruption executor smoke only; no candidate quality certification":acceptance?"frozen synthetic evaluation; no statistical independence or production approval claim":"development calibration fixture, never independent acceptance");
        report.put("productionEngineChanged",false); report.put("observations",observations);
        Set<String> privateAliases=new HashSet<>(); dataset.path("sources").forEach(s->{ if(s.path("kind").asText().equals("PRIVATE_NODE")) privateAliases.add(s.path("alias").asText()); });
        report.put("privateLeaks",observations.stream().mapToLong(o->((List<String>)o.get("retrievedAliases")).stream().filter(privateAliases::contains).count()).sum());
        report.put("forbiddenLeaks",observations.stream().mapToLong(o->((List<String>)o.get("retrievedAliases")).stream().filter(((List<String>)o.get("forbiddenAliases"))::contains).count()).sum());

        if(acceptance) {
            var freeze=synthetic?com.specagent.retrieval.eval.RetrievalEvaluationSnapshot.parse("""
                {"thresholdStatus":"AI_BASELINE_FROZEN","proposedThresholds":{"macroSourceRecallAt8":0.85,
                "firstRelevantMrr":0.75,"multiSourceAllOfCoverage":0.75,"staleSourceLeakCount":0,"permissionLeakCount":0}}
                """):evaluationSnapshot.freeze();
            var tNode=freeze.path("proposedThresholds");
            if(tNode.isMissingNode() || tNode.isNull()) tNode=freeze.path("approvedThresholds");
            double pureZeroHit=ai?0.0:tNode.required("pureNoAnswerZeroHitCorrectness").doubleValue();
            int maxStale=tNode.required("staleSourceLeakCount").intValue();
            int maxPerm=tNode.required("permissionLeakCount").intValue();
            var thresholds=new com.specagent.retrieval.eval.RetrievalQualityScorer.QualityThresholds(
                freeze.path("thresholdStatus").asText(),
                tNode.required("macroSourceRecallAt8").doubleValue(),
                tNode.required("firstRelevantMrr").doubleValue(),
                tNode.required("multiSourceAllOfCoverage").doubleValue(),
                pureZeroHit,
                maxStale,
                maxPerm
            );
            List<com.specagent.retrieval.eval.RetrievalQualityScorer.QueryObservation> queryObservations=new ArrayList<>();
            for(var obs:observations) {
                queryObservations.add(new com.specagent.retrieval.eval.RetrievalQualityScorer.QueryObservation(
                    (String)obs.get("id"),
                    (String)obs.get("category"),
                    (List<String>)obs.get("expectedAliases"),
                    (List<String>)obs.get("forbiddenAliases"),
                    (List<String>)obs.get("backgroundAliases"),
                    (List<String>)obs.get("retrievedAliases"),
                    Boolean.TRUE.equals(obs.get("vectorUnavailable")),
                    (Long)obs.get("elapsedMs"),
                    (Integer)obs.get("invalidSourceCount")
                ));
            }
            Set<String> staleAliasesSet=stale.stream().map(aliases::get).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            var qualityReport=ai
                ?com.specagent.retrieval.eval.RetrievalQualityScorer.evaluateAiAudited(queryObservations,thresholds,staleAliasesSet)
                :com.specagent.retrieval.eval.RetrievalQualityScorer.evaluate(queryObservations,thresholds,staleAliasesSet);
            // 顶层指标必须与质量报告严格一致，单一事实源
            report.put("recallAt8",qualityReport.macroSourceRecallAt8());
            report.put("mrr",qualityReport.firstRelevantMrr());
            report.put("multiSourceAllOfCoverage",qualityReport.multiSourceAllOfCoverage());
            report.put("noAnswerQueries",qualityReport.pureNoAnswerCount());
            report.put("zeroHitCorrectness",qualityReport.pureNoAnswerZeroHitRate());
            report.put("canonicalStaleLeaks",qualityReport.staleSourceLeakCount());
            report.put("permissionLeaks",qualityReport.permissionLeakCount());
            report.put("qualityReport",qualityReport.summary());
            report.put("qualityFailureReasons",qualityReport.failureReasons());

            report.put("evaluationMode",mode.name());
            report.put("freezeSha256",activeClaim.freezeSha256());
            report.put("runId",activeClaim.runId());
            report.put("sourceIdentityValidity",qualityReport.summary().get("sourceIdentityValidity"));
            boolean passed=qualityReport.isPass() && leaks==0 && invalidSources==0 && observations.size()==(synthetic?3:68);
            journal.prepareQuality(report,passed);
        } else {
            report.put("recallAt8",recall/labelled);
            report.put("mrr",mrr/labelled);
            report.put("noAnswerQueries",empty);
            report.put("zeroHitCorrectness",(double)correctEmpty/empty);
            report.put("canonicalStaleLeaks",invalidSources);
            report.put("permissionLeaks",leaks);
            report.put("multiSourceAllOfCoverage",observations.stream().filter(o->o.get("category").equals("MULTI_SOURCE"))
                .mapToDouble(o->((List<?>)o.get("retrievedAliases")).containsAll((List<?>)o.get("expectedAliases"))?1:0).average().orElse(0));
        }
        if(!acceptance) Files.writeString(Path.of("../docs/v2/evidence/SHARED_RAG_52_QUERY_EVALUATION.json"),write(report));
        if(!acceptance) {
            assertTrue(observations.size()>=50); assertEquals(0,leaks); assertEquals(0,invalidSources);
            assertTrue(observations.stream().allMatch(o->Boolean.FALSE.equals(o.get("vectorUnavailable"))));
        }
        }
        // Resource sampler close must succeed before a completed quality report is eligible for a terminal verdict.
        if(journal!=null) journal.bodyFinished();
      } catch(Exception | AssertionError failure) { if(journal!=null) journal.failed(failure); throw failure; }
    }

    static void requireReviewedCandidate(com.fasterxml.jackson.databind.JsonNode dataset) {
        assertEquals("APPROVED",dataset.path("humanReviewStatus").asText(),"Independent acceptance requires actual human label review before execution");
        assertFalse(dataset.path("reviewedBy").asText().isBlank());
        assertDoesNotThrow(()->Instant.parse(dataset.path("reviewedAt").asText()));
        assertTrue(java.util.stream.StreamSupport.stream(dataset.path("queries").spliterator(),false)
            .allMatch(query->query.path("labelReview").asText().equals("HUMAN_REVIEWED")));
    }

    @Test void pendingIndependentCandidateIsRejectedBeforePythonOrIndexing() throws Exception {
        for(String candFile : List.of("acceptance-candidate.v1.json", "acceptance-candidate.v2.json", "acceptance-candidate.v2-revised.json", "acceptance-candidate.v2.1.json", "acceptance-candidate.v2.2.json")) {
            Path candPath = Path.of("../contracts/retrieval/evaluation/" + candFile);
            if(!Files.exists(candPath)) continue;
            String raw = Files.readString(candPath);
            var dataset = JSON.readTree(raw);
            if(dataset.path("humanReviewStatus").asText().equals("PENDING")) {
                long grants = jdbc.queryForObject("SELECT count(*) FROM retrieval_scope_grants WHERE corpus_id=?", Long.class, project.id());
                String freezeFile = candFile.replace("candidate", "freeze");
                var freeze = JSON.readTree(Files.readString(Path.of("../contracts/retrieval/evaluation/" + freezeFile)));
                assertThrows(Exception.class, () -> com.specagent.retrieval.eval.RetrievalAcceptanceGateValidator.validateForExecution(
                    dataset, raw, freeze, Path.of(".."), "INDEPENDENT_ACCEPTANCE_CANDIDATE", 68, 28
                ));
                assertNull(python);
                assertEquals(grants, jdbc.queryForObject("SELECT count(*) FROM retrieval_scope_grants WHERE corpus_id=?", Long.class, project.id()));
            }
        }
    }

    @Test void liveUnchangedConfiguredModelInvokesBothSharedRetrievalToolsWithFullCatalog() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_RAG_U3_LIVE_MODEL")));
        startPython();
        UUID helpGeneration=store.ensureHelpGeneration(); while(shared.splitOneHelp(helpGeneration)) { }
        while(shared.indexOneBatch(CuratedHelpSources.CORPUS,helpGeneration)) { }
        UUID projectGeneration=store.ensureProjectGeneration(project.id()); while(shared.indexOneBatch(project.id(),projectGeneration)) { }
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://localhost:5434/spec_agent",
            System.getenv().getOrDefault("SPEC_AGENT_DB_USER","spec_agent"),System.getenv().getOrDefault("SPEC_AGENT_DB_PASSWORD","spec_agent_dev"));
        var target=new com.specagent.modelsettings.JdbcOpenCodeSettingsRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(source),
            new com.specagent.modelsettings.ModelCredentialCrypto(System.getenv().getOrDefault("SPEC_AGENT_SECRET_MASTER_KEY",""),"","data/secret-master.key")).find().orElseThrow();
        org.mockito.Mockito.when(modelSettings.find()).thenReturn(Optional.of(target));
        var real=new com.specagent.model.provider.HttpOpenCodeZenTransport(new com.fasterxml.jackson.databind.ObjectMapper(),
            com.specagent.model.provider.OpenCodeZenTransport.BASE_URL,45,System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_PROXY","DIRECT"));
        org.mockito.Mockito.when(modelTransport.completeNativeGa(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(i->real.completeNativeGa(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5)));
        var diagnostic=new java.util.concurrent.atomic.AtomicReference<String>("NO_PROVIDER_EXCEPTION");
        org.mockito.Mockito.doAnswer(i->{
            try { return real.streamNativeGa(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6)); }
            catch(com.specagent.model.provider.OpenCodeModelException failure) { diagnostic.set(failure.category()+":"+failure.httpStatus()); throw failure; }
            catch(com.specagent.model.provider.GaNativeStreamException failure) { diagnostic.set(failure.getMessage()+":"+failure.frames()); throw failure; }
            catch(RuntimeException failure) { diagnostic.set(failure.getClass().getSimpleName()+":"+failure.getMessage()); throw failure; }
        }).when(modelTransport).streamNativeGa(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        UUID thread=gaConversations.createThread().id(); List<String> qualified=new ArrayList<>();
        try {
            for(var task:List.of(Map.entry("help.search","请必须调用 help.search 查询如何创建项目和打开工作台，然后根据返回帮助回答。不要创建项目，不要导航。"),
                Map.entry("project.content.discover","请必须调用 project.content.discover，projectId 为 "+project.id()+"，query 为 EXP-2026-A，查询该项目实际内容并引用返回来源回答。不要读取别的项目，不要写入，不要导航。"))) {
                var run=gaApplication.createRun(thread,task.getValue(),null);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
                while(System.nanoTime()<until && !gaRuns.findById(run.id()).orElseThrow().status().isTerminal()) Thread.sleep(50);
                assertEquals(com.specagent.assistant.conversation.GlobalAssistantRunStatus.COMPLETED,gaRuns.findById(run.id()).orElseThrow().status(),diagnostic.get()+" events="+gaEvents.findByRun(run.id()).stream().filter(e->e.type().equals("RUN_FAILED")).toList());
                assertTrue(jdbc.queryForObject("SELECT count(*) FROM capability_invocations WHERE run_id=? AND capability_id=? AND status='SUCCEEDED'",Integer.class,run.id(),task.getKey())>0);
                assertTrue(jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='TOOL_COMPLETED' AND jsonb_array_length(payload->'resourceRefs')>0",Integer.class,run.id())>0);
                assertEquals(10,jdbc.queryForObject("SELECT jsonb_array_length(allowed_tools::jsonb) FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
                qualified.add(task.getKey());
            }
            Files.writeString(Path.of("../docs/v2/evidence/SHARED_RAG_U3_REAL_MODEL.json"),write(Map.of("recordedAt",Instant.now().toString(),"status","PASS","boundary","unchanged configured model + full 10-tool catalog + actual Java/Python/Ollama/pgvector product dispatch","model",target.selectedModel(),"capabilities",qualified,"productionEngineChanged",false)));
        } finally {
            jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE thread_id=? AND status IN ('CREATED','RUNNING')",thread);
            conversationDeletion.deleteThread(thread);
        }
    }

    @Test void realHelpAndProjectDiscoveryUseSameHostRpcAndFenceCancellation() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_RAG_U3_TEST")));
        startPython();
        UUID generation=store.ensureHelpGeneration();
        int split=0; while(shared.splitOneHelp(generation)) { assertTrue(++split<=3); }
        assertEquals(3,jdbc.queryForObject("SELECT count(DISTINCT metadata->>'helpDocId') FROM retrieval_entries WHERE corpus_id=?",Integer.class,CuratedHelpSources.CORPUS));
        int indexed=0; while(shared.indexOneBatch(CuratedHelpSources.CORPUS,generation)) assertTrue(++indexed<=3);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE corpus_id=? AND embedding IS NULL",Integer.class,CuratedHelpSources.CORPUS));
        var helpRows=jdbc.queryForList("SELECT * FROM retrieval_entries WHERE corpus_id=?",CuratedHelpSources.CORPUS);
        assertTrue(helpRows.stream().allMatch(store::canonicalCurrent));
        var catalog=catalogs.current();
        assertEquals(10,catalog.tools().size());
        assertTrue(catalog.descriptors().stream().filter(d->Set.of("help.search","project.content.discover").contains(d.capabilityId())).allMatch(d->d.readOnly() && d.sideEffectClass().equals("NONE")));
        UUID thread=UUID.randomUUID(),run=UUID.randomUUID(),lease=UUID.randomUUID();
        jdbc.update("INSERT INTO global_assistant_threads(id,org_id,owner_user_id) VALUES(?,'00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000002')",thread);
        jdbc.update("INSERT INTO global_assistant_runs(id,thread_id,status) VALUES(?,?,'CREATED')",run,thread);
        var executionScope=new com.specagent.assistant.runtime.GaExecutionStore.Scope(run,1,lease);
        executions.initialize(executionScope,new com.specagent.assistant.runtime.GaExecutionStore.Binding(thread,UUID.randomUUID(),"OPENCODE_ZEN","test-model","test",Instant.now().plusSeconds(180),catalog.tools()),catalog.descriptors());
        jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?",run);
        gaConversations.appendUserMessage(thread,"检索来源及失败展示验收",run);
        try {
            UUID projectGeneration=store.ensureProjectGeneration(project.id());
            while(shared.indexOneBatch(project.id(),projectGeneration)) { }
            Map<String,Object> helpArgs=Map.of("query","如何创建项目并打开工作台？","limit",3);
            var helpResult=invokeRecorded(executionScope,catalog,"help-one","help.search",helpArgs);
            assertEquals("SUCCEEDED",helpResult.path("status").asText(),helpResult.toString());
            assertFalse(helpResult.path("content").path("sources").isEmpty());
            for(var item:helpResult.path("content").path("sources")) {
                assertEquals("HELP",item.path("source").path("scope").asText());
                assertTrue(item.path("source").path("projectId").isNull());
            }
            Map<String,Object> projectArgs=Map.of("query","EXP-2026-A","projectId",project.id().toString(),"limit",3);
            var projectResult=invokeRecorded(executionScope,catalog,"project-one","project.content.discover",projectArgs);
            assertEquals("SUCCEEDED",projectResult.path("status").asText(),projectResult.toString());
            assertEquals(project.id().toString(),projectResult.path("content").path("candidates").get(0).path("projectId").asText());
            assertFalse(projectResult.toString().contains(other.id().toString()));
            var wire=capabilityBody(executionScope,catalog,"project-one","project.content.discover",projectArgs);
            assertEquals(projectResult,capabilityBroker.invoke(wire).body());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM capability_invocations WHERE run_id=?",Integer.class,run));
            try(var http=HttpClient.newHttpClient()) {
                var health=http.send(HttpRequest.newBuilder(URI.create(properties.getBaseUrl()+"/internal/v1/retrieval/health")).header("X-Spec-Agent-Internal-Token",properties.getInternalSecret()).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,health.statusCode()); assertTrue(JSON.readTree(health.body()).path("storeReady").asBoolean()); assertTrue(JSON.readTree(health.body()).path("ollamaReady").asBoolean());
                assertEquals(401,http.send(HttpRequest.newBuilder(URI.create(properties.getBaseUrl()+"/internal/v1/retrieval/health")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            properties.setBaseUrl("http://127.0.0.1:1");
            var failedArgs=Map.<String,Object>of("query","帮助服务失败验收","limit",3);
            var failed=invokeRecorded(executionScope,catalog,"help-unavailable","help.search",failedArgs);
            assertEquals("FAILED",failed.path("status").asText()); assertEquals("RETRIEVAL_UNAVAILABLE",failed.path("errorCode").asText());
            assertEquals(failed,capabilityBroker.invoke(capabilityBody(executionScope,catalog,"help-unavailable","help.search",failedArgs)).body());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ga_execution_calls WHERE run_id=? AND kind='TOOL' AND status='UNKNOWN'",Integer.class,run));
            var helpRequest=store.issueHelp("项目",3,run);
            jdbc.update("UPDATE global_assistant_runs SET cancel_requested_at=now() WHERE id=?",run);
            assertThrows(IllegalStateException.class,()->store.candidates(candidate(helpRequest,List.of("project-lexical"),null)));
            assertEquals(409,capabilityBroker.invoke(wire).status());
            gaLifecycle.interruptAndTerminalize(run);
            if("true".equals(System.getenv("SPEC_AGENT_RAG_U3_BROWSER"))) runRetrievalBrowser(thread);
            Files.writeString(Path.of("../docs/v2/evidence/SHARED_RAG_U3_TOOL_INTEGRATION.json"),write(Map.of("recordedAt",Instant.now().toString(),"status","PASS","boundary","real Python/Ollama/Java broker/pgvector; recorded native calls seeded at model boundary","catalogSize",10,"helpDocuments",3,"sharedIndex",true,"cancelFence",true,"productionEngineChanged",false)));
        } finally {
            jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?",run);
            conversationDeletion.deleteThread(thread);
        }
    }
    com.fasterxml.jackson.databind.JsonNode invokeRecorded(com.specagent.assistant.runtime.GaExecutionStore.Scope scope,
            com.specagent.assistant.tool.GaCatalogProjection.Snapshot catalog,String id,String capability,Map<String,Object> args) {
        UUID call=UUID.randomUUID(); var reservation=executions.reserve(scope,call,"MODEL",com.specagent.common.Hashes.sha256Hex("fixture:"+id));
        var response=new com.specagent.model.contract.GaModelContract.Response(com.specagent.model.contract.GaModelContract.VERSION,"",List.of(new com.specagent.model.contract.GaModelContract.ToolCall(id,capability.replace('.','_'),args)),"tool_calls",new com.specagent.model.contract.GaModelContract.Usage(1,1));
        executions.complete(scope,call,catalogs.canonical(response));
        var result=capabilityBroker.invoke(capabilityBody(scope,catalog,id,capability,args));
        assertEquals(200,result.status(),result.body().toString());
        return (com.fasterxml.jackson.databind.JsonNode)result.body();
    }
    String capabilityBody(com.specagent.assistant.runtime.GaExecutionStore.Scope scope,
            com.specagent.assistant.tool.GaCatalogProjection.Snapshot catalog,String id,String capability,Map<String,Object> args) {
        return catalog==null?"":catalogs.canonical(Map.of("protocolVersion","ga-capability-invocation.v1","runId",scope.runId().toString(),"executionEpoch",1,
            "leaseId",scope.leaseId().toString(),"toolCallId",id,"capabilityId",capability,"descriptorVersion","1","catalogHash",catalog.hash(),"arguments",args,"argumentsHash",com.specagent.common.Hashes.sha256Hex(catalogs.canonical(args))));
    }

    void runRetrievalBrowser(UUID thread) throws Exception {
        int browserPort; try(var socket=new java.net.ServerSocket(0)) { browserPort=socket.getLocalPort(); }
        var builder=new ProcessBuilder("node",Path.of("../frontend/node_modules/@playwright/test/cli.js").toAbsolutePath().toString(),"test","e2e/global-assistant-retrieval.spec.ts","--workers=1");
        builder.directory(Path.of("../frontend").toFile()); builder.environment().put("PLAYWRIGHT_BACKEND_PORT",Integer.toString(port));
        builder.environment().put("PLAYWRIGHT_PORT",Integer.toString(browserPort)); builder.environment().put("PLAYWRIGHT_CHANNEL","msedge");
        builder.environment().put("SPEC_AGENT_GA_RETRIEVAL_THREAD",thread.toString());
        builder.environment().put("SPEC_AGENT_GA_RETRIEVAL_SCREENSHOT",Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_BROWSER_RAG_SOURCES.png").toAbsolutePath().toString());
        Path browserLog=Files.createTempFile("shared-rag-browser-",".log"); builder.redirectErrorStream(true).redirectOutput(browserLog.toFile());
        Process browser=builder.start();
        try { assertTrue(browser.waitFor(90,TimeUnit.SECONDS)); assertEquals(0,browser.exitValue(),Files.readString(browserLog)); }
        finally {
            var children=browser.descendants().toList(); children.forEach(ProcessHandle::destroyForcibly); browser.destroyForcibly(); browser.waitFor(5,TimeUnit.SECONDS);
            for(var child:children) if(child.isAlive()) child.onExit().get(5,TimeUnit.SECONDS);
            for(int attempt=0;attempt<30;attempt++) try { Files.deleteIfExists(browserLog); break; } catch(java.nio.file.FileSystemException busy) { if(attempt==29) throw busy; Thread.sleep(100); }
        }
        Files.writeString(Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_REAL_RAG_BROWSER.json"),write(Map.of("recordedAt",Instant.now().toString(),"status","PASS","boundary","actual Vue/browser/host events/Java/Python/Ollama/pgvector; native calls explicitly seeded at model boundary","sourceCards",true,"refreshNoDuplicate",true,"failedToolHonest",true,"productionEngineChanged",false)));
    }

    void startPython() throws Exception { startPython(null); }
    void startPython(String ollamaUrl) throws Exception {
        int brainPort; try(var socket=new java.net.ServerSocket(0)) { brainPort=socket.getLocalPort(); }
        var builder=new ProcessBuilder(Path.of(System.getenv().getOrDefault("SPEC_AGENT_GA_TEST_PYTHON","../agent-brain/.venv/Scripts/python.exe")).toAbsolutePath().toString(),"-m","uvicorn",
                "spec_agent_brain.app:create_app","--factory","--host","127.0.0.1","--port",Integer.toString(brainPort));
        builder.directory(Path.of("../agent-brain").toFile());
        builder.environment().put("SPEC_AGENT_BRAIN_MODEL_MODE","broker");
        builder.environment().put("PYTHONFAULTHANDLER","1");
        if(ollamaUrl!=null) builder.environment().put("SPEC_AGENT_OLLAMA_URL",ollamaUrl);
        builder.environment().put("SPEC_AGENT_INTERNAL_BROKER_URL","http://127.0.0.1:"+port+"/internal/v1/model-inference");
        builder.environment().put("SPEC_AGENT_BRAIN_INTERNAL_SECRET",properties.getInternalSecret());
        // Internal clients select direct transport; preserve the operator proxy environment.
        log=journal==null?Files.createTempFile("shared-rag-python-",".log"):Files.createFile(journal.artifactsDirectory().resolve("python.log"));
        builder.redirectErrorStream(true).redirectOutput(log.toFile()); python=builder.start();
        properties.setBaseUrl("http://127.0.0.1:"+brainPort);
        try(var http=HttpClient.newHttpClient()) {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<until && python.isAlive()) {
                try { if(http.send(HttpRequest.newBuilder(URI.create(properties.getBaseUrl()+"/health")).timeout(Duration.ofSeconds(1))
                        .GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200) return; } catch(Exception ignored) { }
                Thread.sleep(50);
            }
        }
        fail("Python retrieval service did not become ready: "+Files.readString(log));
    }
}
