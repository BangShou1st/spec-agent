package com.specagent.retrieval;

import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.retrieval.protocol.*;
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

    @BeforeEach void fixture() {
        project=projects.createProject("shared-rag-fixture-"+UUID.randomUUID());
        other=projects.createProject("shared-rag-isolation-"+UUID.randomUUID());
        node=nodes.createRootNode(project.id(),project.activeRouteId(),"订单导出使用 CSV 文件，导出编号 EXP-2026-A。",null,List.of(),true).id();
        nodes.createRootNode(other.id(),other.activeRouteId(),"权限隔离秘密：另一个项目的退款账号。",null,List.of(),true);
        oldBase=properties.getBaseUrl();
    }
    @AfterEach void cleanup() throws Exception {
        stopCandidatePython();
        properties.setBaseUrl(oldBase);
        for(var item:List.of(project,other)) {
            jdbc.update("DELETE FROM retrieval_scope_grants WHERE corpus_id=?",item.id());
            jdbc.update("DELETE FROM retrieval_index_jobs WHERE corpus_id=?",item.id());
            jdbc.update("DELETE FROM retrieval_index_heads WHERE corpus_id=?",item.id());
            jdbc.update("DELETE FROM retrieval_index_generations WHERE corpus_id=?",item.id());
            deletion.deleteProject(item.id());
        }
    }
    void stopCandidatePython() throws Exception {
        if(python!=null) {
            var children=python.descendants().toList(); children.forEach(ProcessHandle::destroyForcibly);
            python.destroyForcibly(); python.waitFor(5,TimeUnit.SECONDS);
            for(var child:children) if(child.isAlive()) child.onExit().get(5,TimeUnit.SECONDS);
            python=null;
        }
        if(log!=null) for(int attempt=0;attempt<30;attempt++) {
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
        assertTrue(Set.of("chinese-queries.v1.json","acceptance-candidate.v1.json").contains(datasetFile));
        Path datasetPath=Path.of("../contracts/retrieval/evaluation/"+datasetFile);
        var dataset=JSON.readTree(Files.readString(datasetPath));
        boolean acceptance=datasetFile.equals("acceptance-candidate.v1.json");
        String reviewStatus=dataset.path("humanReviewStatus").asText();
        assertTrue(Set.of("PENDING","APPROVED").contains(reviewStatus));
        if(acceptance) {
            // A new held-out run is a separate gate, never a silent reuse of the calibration report.
            requireReviewedCandidate(dataset);
            var freeze=JSON.readTree(Files.readString(Path.of("../contracts/retrieval/evaluation/acceptance-freeze.v1.json")));
            assertEquals(freeze.path("datasetSha256").asText(),com.specagent.common.Hashes.sha256Hex(Files.readString(datasetPath)));
            assertEquals(PROFILE,freeze.path("profileId").asText());
            assertEquals("APPROVED_BEFORE_FIRST_RUN",freeze.path("thresholdStatus").asText());
            freeze.path("codeHashes").fields().forEachRemaining(entry->{
                try { assertEquals(entry.getValue().asText(),com.specagent.common.Hashes.sha256Hex(Files.readString(Path.of("../"+entry.getKey())))); }
                catch(java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
            });
        }
        if(reviewStatus.equals("APPROVED")) {
            assertFalse(dataset.path("reviewedBy").asText().isBlank());
            assertDoesNotThrow(()->Instant.parse(dataset.path("reviewedAt").asText()));
            assertTrue(java.util.stream.StreamSupport.stream(dataset.path("queries").spliterator(),false).allMatch(q->q.path("labelReview").asText().equals("HUMAN_REVIEWED")));
        }
        startPython();
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
        // Intentionally leave the derived projection untouched: final canonical checks must still reject it.
        for(UUID item:stale) jdbc.update("UPDATE nodes SET retracted_at=now() WHERE id=?",item);
        var baseline=new com.specagent.retrieval.search.HybridRetriever(entries,
            new com.specagent.retrieval.search.VectorCandidateRetriever(new com.specagent.retrieval.embedding.NoopEmbeddingGateway(),entries));
        List<Map<String,Object>> observations=new ArrayList<>(); List<Long> timings=new ArrayList<>();
        double recall=0,mrr=0,baseRecall=0; int labelled=0,empty=0,correctEmpty=0,invalidSources=0,leaks=0; long heapPeak=0;
        Set<String> excluded=Set.of("node:"+node);
        for(var labelledQuery:dataset.path("queries")) {
            String category=labelledQuery.path("category").asText(),query=labelledQuery.path("query").asText();
            List<String> expected=new ArrayList<>(),forbidden=new ArrayList<>();
            labelledQuery.path("expectedAliases").forEach(a->expected.add(a.asText()));
            labelledQuery.path("forbiddenAliases").forEach(a->forbidden.add(a.asText()));
            List<RetrievalScope> scopes=new ArrayList<>();
            labelledQuery.path("scopes").forEach(value->scopes.add(RetrievalScope.valueOf(value.asText())));
            if(scopes.isEmpty()) scopes.add(category.equals("RESOURCE_CONTENT")?RetrievalScope.RESOURCE:RetrievalScope.PROJECT);
            var request=new RetrievalQuery(project.id(),null,null,"EXPLICIT_SEARCH",query,scopes,8,8000,Set.of(),Set.of());
            long begin=System.nanoTime(); var result=shared.search(request,UUID.randomUUID(),"CONTEXT_PROJECTION",excluded);
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin); timings.add(elapsed);
            List<String> actual=result.items().stream().map(i->aliases.getOrDefault(UUID.fromString(i.source().sourceRef().split(":")[1]),"UNLABELLED")).distinct().toList();
            List<String> legacy=baseline.retrieve(request).stream().filter(c->!excluded.contains(c.entry().sourceRef())).limit(8)
                .map(c->aliases.getOrDefault(c.entry().sourceId(),"UNLABELLED")).distinct().toList();
            for(var item:result.items()) {
                if(!project.id().equals(item.source().projectId()) || forbidden.contains(aliases.get(UUID.fromString(item.source().sourceRef().split(":")[1])))) leaks++;
                if(stale.contains(UUID.fromString(item.source().sourceRef().split(":")[1]))) invalidSources++;
            }
            if(!expected.isEmpty()) {
                labelled++; recall+=(double)expected.stream().filter(actual::contains).count()/expected.size();
                baseRecall+=(double)expected.stream().filter(legacy::contains).count()/expected.size();
                for(int rank=0;rank<actual.size();rank++) if(expected.contains(actual.get(rank))) { mrr+=1.0/(rank+1); break; }
            } else { empty++; if(actual.isEmpty()) correctEmpty++; }
            heapPeak=Math.max(heapPeak,java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            var observation=new LinkedHashMap<String,Object>();
            observation.put("id",labelledQuery.path("id").asText()); observation.put("category",category); observation.put("query",query);
            observation.put("expectedAliases",expected); observation.put("retrievedAliases",actual); observation.put("legacyLexicalAliases",legacy);
            observation.put("elapsedMs",elapsed); observation.put("vectorUnavailable",result.vectorUnavailable()); observation.put("labelReview",labelledQuery.path("labelReview").asText());
            observation.put("sources",result.items().stream().map(Ranked::source).toList()); observations.add(observation);
        }
        Collections.sort(timings); var report=new LinkedHashMap<String,Object>();
        report.put("recordedAt",Instant.now().toString()); report.put("boundary","actual Python + local qwen3-embedding:0.6b + authenticated Java RPC + existing pgvector; isolated canonical test sources");
        report.put("authorship",dataset.path("authorship").asText()); report.put("humanReviewStatus",reviewStatus); report.put("queryCount",observations.size());
        report.put("datasetFile",datasetFile); report.put("datasetRole",dataset.path("datasetRole").asText());
        report.put("datasetSha256",com.specagent.common.Hashes.sha256Hex(Files.readString(datasetPath)));
        report.put("profileId",PROFILE); report.put("recallAt8",recall/labelled); report.put("mrr",mrr/labelled); report.put("javaLegacyLexicalRecallAt8",baseRecall/labelled);
        report.put("noAnswerQueries",empty); report.put("zeroHitCorrectness",(double)correctEmpty/empty); report.put("canonicalStaleLeaks",invalidSources); report.put("permissionLeaks",leaks);
        report.put("p50Ms",timings.get(timings.size()/2)); report.put("p95Ms",timings.get((int)Math.ceil(timings.size()*0.95)-1));
        report.put("javaHeapObservedPeakBytes",heapPeak); report.put("resourceMeasurementLimit","Sampled observations; Java heap after each query, Python working set and whole GPU device shared with other apps"); report.put("resourceSamples",resources.report());
        report.put("multiSourceAllOfCoverage",observations.stream().filter(o->o.get("category").equals("MULTI_SOURCE"))
            .mapToDouble(o->((List<?>)o.get("retrievedAliases")).containsAll((List<?>)o.get("expectedAliases"))?1:0).average().orElse(0));
        report.put("maxVectorDistance",0.50); report.put("evaluationUse",acceptance?"independent candidate run under frozen config; not automatically production-approved":"development calibration fixture, never independent acceptance");
        report.put("productionEngineChanged",false); report.put("observations",observations);
        Files.writeString(Path.of("../docs/v2/evidence/"+(acceptance?"SHARED_RAG_ACCEPTANCE_CANDIDATE_EVALUATION.json":"SHARED_RAG_52_QUERY_EVALUATION.json")),write(report));
        assertTrue(observations.size()>=50); assertEquals(0,leaks); assertEquals(0,invalidSources);
        assertTrue(observations.stream().allMatch(o->Boolean.FALSE.equals(o.get("vectorUnavailable"))));
        }
    }

    static void requireReviewedCandidate(com.fasterxml.jackson.databind.JsonNode dataset) {
        assertEquals("APPROVED",dataset.path("humanReviewStatus").asText(),"Independent acceptance requires actual human label review before execution");
        assertFalse(dataset.path("reviewedBy").asText().isBlank());
        assertDoesNotThrow(()->Instant.parse(dataset.path("reviewedAt").asText()));
        assertTrue(java.util.stream.StreamSupport.stream(dataset.path("queries").spliterator(),false)
            .allMatch(query->query.path("labelReview").asText().equals("HUMAN_REVIEWED")));
    }

    @Test void pendingIndependentCandidateIsRejectedBeforePythonOrIndexing() throws Exception {
        var dataset=JSON.readTree(Files.readString(Path.of("../contracts/retrieval/evaluation/acceptance-candidate.v1.json")));
        Assumptions.assumeTrue(dataset.path("humanReviewStatus").asText().equals("PENDING"));
        long grants=jdbc.queryForObject("SELECT count(*) FROM retrieval_scope_grants WHERE corpus_id=?",Long.class,project.id());
        assertThrows(AssertionError.class,()->requireReviewedCandidate(dataset));
        assertNull(python);
        assertEquals(grants,jdbc.queryForObject("SELECT count(*) FROM retrieval_scope_grants WHERE corpus_id=?",Long.class,project.id()));
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
        jdbc.update("INSERT INTO global_assistant_threads(id) VALUES(?)",thread);
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
        log=Files.createTempFile("shared-rag-python-",".log");
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
