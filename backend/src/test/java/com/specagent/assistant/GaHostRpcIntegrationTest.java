package com.specagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.assistant.conversation.ConversationDeleteService;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.modelsettings.OpenCodeSettings;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual HTTP and host database, deterministic provider boundary. Python check is explicit opt-in. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GaHostRpcIntegrationTest {
    @Autowired GaExecutionStore executions;
    @Autowired ConversationDeleteService deletion;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired AgentBrainProperties properties;
    @org.springframework.boot.test.mock.mockito.SpyBean com.specagent.assistant.tool.GaCatalogProjection catalogs;
    @MockBean com.specagent.skill.importing.GitSkillImporter gitImporter;
    @org.springframework.boot.test.mock.mockito.SpyBean com.specagent.capability.CapabilityRuntime capabilityRuntime;
    @Autowired com.specagent.workspace.project.ProjectDeletionService projectDeletion;
    @LocalServerPort int port;
    @MockBean OpenCodeSettingsRepository settings;
    @MockBean OpenCodeZenTransport transport;
    ObjectMapper mapper = new ObjectMapper();
    UUID thread, run, lease, binding;
    GaModelContract.Request request;
    GaExecutionStore.Scope scope;
    Instant revision;
    com.specagent.assistant.tool.GaCatalogProjection.Snapshot businessCatalog;

    @BeforeEach void fixture(TestInfo info) throws Exception {
        thread=UUID.randomUUID(); run=UUID.randomUUID(); lease=UUID.randomUUID(); binding=UUID.randomUUID();
        jdbc.update("INSERT INTO global_assistant_threads(id) VALUES(?)", thread);
        jdbc.update("INSERT INTO global_assistant_runs(id,thread_id,status) VALUES(?,?,'CREATED')", run, thread);
        var template = GaModelContract.readRequest(Files.readString(Path.of("../contracts/global-assistant/fixtures/ga-model-request-valid.json")));
        businessCatalog=info.getTestMethod().orElseThrow().getName().startsWith("capability") ? catalogs.current() : null;
        if (info.getTestMethod().orElseThrow().getName().equals("capabilityLiveFrameworkBusinessRpcAgainstConfiguredModel")) {
            var readOnly=businessCatalog.descriptors().stream().filter(d -> d.capabilityId().equals("project.list_recent")).toList();
            var tools=businessCatalog.tools().stream().filter(t -> t.name().equals("project_list_recent")).toList();
            businessCatalog=new com.specagent.assistant.tool.GaCatalogProjection.Snapshot(
                    Hashes.sha256Hex(catalogs.canonical(readOnly)),readOnly,tools);
        }
        request = new GaModelContract.Request(GaModelContract.VERSION,run,1,lease,UUID.randomUUID(),"AGENT",binding,
                template.messages(),businessCatalog==null ? template.tools() : businessCatalog.tools(),"auto",1024,false);
        revision=Instant.now();
        when(settings.find()).thenReturn(Optional.of(new OpenCodeSettings("test-key-no-network", "work", "test-model", revision, revision)));
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenReturn(
                new GaModelContract.Response(GaModelContract.VERSION,"rpc-complete",List.of(),"stop",new GaModelContract.Usage(3,2)));
        scope = new GaExecutionStore.Scope(run,1,lease);
        executions.initialize(scope,new GaExecutionStore.Binding(thread,binding,"OPENCODE_ZEN","test-model",
                "opencode-settings:"+revision,Instant.now().plusSeconds(120),request.tools()),
                businessCatalog==null ? List.of() : businessCatalog.descriptors());
        jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", run);
    }
    @AfterEach void cleanup() {
        jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?", run);
        deletion.deleteThread(thread);
        for (UUID project : jdbc.queryForList("SELECT id FROM projects WHERE title=?",UUID.class,"ga-native-test-"+thread))
            projectDeletion.deleteProject(project);
    }
    @Test void authenticatedNativeBrokerReplaysCompletedCallAndRejectsChangedCatalogOrLateOutput() throws Exception {
        assertEquals(401, post("/model-inference",mapper.writeValueAsString(request),"wrong-token").getStatusCode().value());
        assertEquals(200, post("/model-inference",mapper.writeValueAsString(request),properties.getInternalSecret()).getStatusCode().value());
        assertEquals(200, post("/model-inference",mapper.writeValueAsString(request),properties.getInternalSecret()).getStatusCode().value());
        verify(transport,times(1)).completeNativeGa(any(),any(),any(),any(),any(),any());
        assertEquals(1,jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
        var changed = new GaModelContract.Request(GaModelContract.VERSION,run,1,lease,UUID.randomUUID(),"AGENT",binding,
                request.messages(),List.of(),"none",1024,false);
        assertEquals(409,post("/model-inference",mapper.writeValueAsString(changed),properties.getInternalSecret()).getStatusCode().value());
        when(settings.find()).thenReturn(Optional.of(new OpenCodeSettings("changed-key", "work", "test-model",revision,revision.plusSeconds(1))));
        assertEquals(409,post("/model-inference",mapper.writeValueAsString(request),properties.getInternalSecret()).getStatusCode().value());
        jdbc.update("UPDATE global_assistant_runs SET cancel_requested_at=now() WHERE id=?",run);
        assertEquals(409,post("/model-inference",mapper.writeValueAsString(request),properties.getInternalSecret()).getStatusCode().value());
    }
    @Test void checkpointsRejectUnauthenticatedMalformedAndWrongScopeRequests() throws Exception {
        String get=mapper.writeValueAsString(Maps.of("protocolVersion","ga-checkpoint.v1","runId",run.toString(),
                "executionEpoch",1,"leaseId",lease.toString(),"threadId",thread.toString(),
                "namespace","ga:langchain-ga.v1","expectedVersion",0,"checkpointId",null));
        assertEquals(401,post("/checkpoints/GET",get,"wrong").getStatusCode().value());
        assertEquals(200,post("/checkpoints/GET",get,properties.getInternalSecret()).getStatusCode().value());
        assertEquals(400,post("/checkpoints/GET","{}",properties.getInternalSecret()).getStatusCode().value());
        jdbc.update("UPDATE global_assistant_runs SET cancel_requested_at=now() WHERE id=?",run);
        assertEquals(409,post("/checkpoints/GET",get,properties.getInternalSecret()).getStatusCode().value());
    }

    @Test void capabilityOriginCatalogHashValidationAndInvalidAttemptBudget() throws Exception {
        String wire=capabilityWire("c1","project.create",Map.of("title",7));
        assertEquals(401,post("/capabilities",wire,"wrong").getStatusCode().value());
        assertEquals(409,post("/capabilities",wire,properties.getInternalSecret()).getStatusCode().value());
        recordTool("c1","project.create",Map.of("title",7));
        var response=post("/capabilities",wire,properties.getInternalSecret());
        assertEquals(200,response.getStatusCode().value(),response.getBody());
        assertEquals("FAILED",mapper.readTree(response.getBody()).path("status").asText());
        assertEquals("TOOL_ARGUMENT_INVALID",mapper.readTree(response.getBody()).path("errorCode").asText());
        assertEquals(1,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
        assertEquals(400,post("/capabilities",wire.replace("\"argumentsHash\":\"","\"argumentsHash\":\"0"),properties.getInternalSecret()).getStatusCode().value());
        jdbc.update("UPDATE global_assistant_runs SET cancel_requested_at=now() WHERE id=?",run);
        assertEquals(409,post("/capabilities",wire,properties.getInternalSecret()).getStatusCode().value());
    }

    @Test void capabilityNativeBatchAdmitsOnlyReadOnlyBusinessCallsBeforeDispatch() throws Exception {
        var readonly=List.of(new GaModelContract.ToolCall("batch-read-one","project_list_recent",Map.of("limit",1)),
            new GaModelContract.ToolCall("batch-read-two","project_get_summary",Map.of("projectId",UUID.randomUUID().toString())));
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenReturn(new GaModelContract.Response(GaModelContract.VERSION,"",readonly,"tool_calls",new GaModelContract.Usage(3,2)));
        assertEquals(200,post("/model-inference",mapper.writeValueAsString(request),properties.getInternalSecret()).getStatusCode().value());
        for(var disallowed:List.of(new GaModelContract.ToolCall("batch-write","project_create",Map.of("title","must-not-execute")),
            new GaModelContract.ToolCall("batch-nav","ui_navigate",Map.of("destination","PROJECTS")))) {
            var model=new GaModelContract.Request(GaModelContract.VERSION,run,1,lease,UUID.randomUUID(),"AGENT",binding,request.messages(),request.tools(),"auto",1024,false);
            when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenReturn(new GaModelContract.Response(GaModelContract.VERSION,"",List.of(readonly.getFirst(),disallowed),"tool_calls",new GaModelContract.Usage(3,2)));
            assertNotEquals(200,post("/model-inference",mapper.writeValueAsString(model),properties.getInternalSecret()).getStatusCode().value());
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM capability_invocations WHERE run_id=?",Integer.class,run));
        assertEquals(0,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
    }

    @Test void capabilityLocalCreationIsIdempotentAndRepeatWithoutNewObservationStops() throws Exception {
        var args=Map.<String,Object>of("title","ga-native-test-"+thread);
        recordTool("c1","project.create",args);
        String wire=capabilityWire("c1","project.create",args);
        var first=post("/capabilities",wire,properties.getInternalSecret());
        assertEquals(200,first.getStatusCode().value(),first.getBody());
        assertEquals("SUCCEEDED",mapper.readTree(first.getBody()).path("status").asText());
        assertEquals(first.getBody(),post("/capabilities",wire,properties.getInternalSecret()).getBody());
        recordTool("c2","project.create",args);
        var repeated=post("/capabilities",capabilityWire("c2","project.create",args),properties.getInternalSecret());
        assertEquals("REPEATED_TOOL_CALL",mapper.readTree(repeated.getBody()).path("errorCode").asText());
        // Another verified observation permits the read/control flow to advance. The write remains semantically idempotent.
        recordTool("c3","project.list_recent",Map.of("limit",1));
        assertEquals(200,post("/capabilities",capabilityWire("c3","project.list_recent",Map.of("limit",1)),properties.getInternalSecret()).getStatusCode().value());
        recordTool("c4","project.create",args);
        assertEquals(200,post("/capabilities",capabilityWire("c4","project.create",args),properties.getInternalSecret()).getStatusCode().value());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM projects WHERE title=?",Integer.class,args.get("title")));
        assertEquals(4,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"project.create","project.search","project.list_recent","project.get_summary",
            "skill.import.discover","skill.import","ui.navigate","user-input.request"})
    void capabilityRevocationRejectsEveryFrozenCapabilityBeforeBudgetOrSideEffects(String capability) throws Exception {
        Map<String,Object> arguments=Map.of();
        recordTool("revoked",capability,arguments);
        var remaining=businessCatalog.descriptors().stream().filter(d->!d.capabilityId().equals(capability)).toList();
        doReturn(catalogs.snapshot(remaining)).when(catalogs).current();
        var response=post("/capabilities",capabilityWire("revoked",capability,arguments),properties.getInternalSecret());
        assertEquals(409,response.getStatusCode().value());
        assertEquals("GA_CAPABILITY_REVOKED",mapper.readTree(response.getBody()).path("errorCode").asText());
        assertEquals(0,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
        verifyNoInteractions(gitImporter);
    }

    @Test void capabilityReadsUseActualHostProjectionsAndMissingSourceFailsSafely() throws Exception {
        recordTool("p-create","project.create",Map.of("title","ga-native-test-"+thread));
        assertEquals("SUCCEEDED",mapper.readTree(post("/capabilities",capabilityWire("p-create","project.create",
                Map.of("title","ga-native-test-"+thread)),properties.getInternalSecret()).getBody()).path("status").asText());
        UUID project=jdbc.queryForObject("SELECT id FROM projects WHERE title=?",UUID.class,"ga-native-test-"+thread);
        for(var item:List.of(Map.entry("project.search",Map.<String,Object>of("query","ga-native-test-"+thread,"limit",1)),
                Map.entry("project.list_recent",Map.<String,Object>of("limit",1)),
                Map.entry("project.get_summary",Map.<String,Object>of("projectId",project.toString())))) {
            String id=item.getKey(); recordTool(id,id,item.getValue());
            var result=mapper.readTree(post("/capabilities",capabilityWire(id,id,item.getValue()),properties.getInternalSecret()).getBody());
            assertEquals("SUCCEEDED",result.path("status").asText(),result.toString());
            assertTrue(result.path("content").toString().contains(project.toString()));
            assertTrue(result.path("sourceRefs").toString().contains(project.toString()));
        }
        var missing=Map.<String,Object>of("projectId",UUID.randomUUID().toString());
        recordTool("missing","project.get_summary",missing);
        var result=mapper.readTree(post("/capabilities",capabilityWire("missing","project.get_summary",missing),properties.getInternalSecret()).getBody());
        assertEquals("FAILED",result.path("status").asText()); assertTrue(result.path("sourceRefs").isEmpty());
    }

    @Test void capabilitySkillStagingPersistsOnceAndRequiresManualInstallWithoutEnabling() throws Exception {
        byte[] markdown=("---\nname: ga-staging-test\ndescription: Test staging only\n---\nDo nothing.\n").getBytes(StandardCharsets.UTF_8);
        var files=List.of(new com.specagent.skill.importing.SkillSourceFile("SKILL.md",markdown,
                com.specagent.skill.domain.SkillPackageFile.FileKind.SKILL_MD));
        when(gitImporter.fetchTree(anyString(),isNull(),any())).thenReturn(new com.specagent.skill.importing.GitSkillImporter.TreeInventory(
                "a".repeat(40),files,markdown.length,List.of()));
        when(gitImporter.importHttps(anyString(),isNull(),anyString())).thenReturn(new com.specagent.skill.importing.GitSkillImporter.ExtractedResult(
                "a".repeat(40),markdown,files,markdown.length));
        var args=Map.<String,Object>of("url","https://example.org/ga-test.git");
        recordTool("discover","skill.import.discover",args);
        var discovery=mapper.readTree(post("/capabilities",capabilityWire("discover","skill.import.discover",args),properties.getInternalSecret()).getBody());
        assertEquals("SUCCEEDED",discovery.path("status").asText(),discovery.toString());
        recordTool("stage","skill.import",args);
        String body=capabilityWire("stage","skill.import",args);
        var staged=post("/capabilities",body,properties.getInternalSecret());
        var result=mapper.readTree(staged.getBody());
        assertEquals("SUCCEEDED",result.path("status").asText(),staged.getBody());
        UUID id=UUID.fromString(result.path("content").path("stagedImportId").asText());
        try {
            assertEquals(staged.getBody(),post("/capabilities",body,properties.getInternalSecret()).getBody());
            verify(gitImporter,never()).importHttps(anyString(),isNull(),anyString());
            assertEquals("STAGED",jdbc.queryForObject("SELECT status FROM skill_staged_imports WHERE id=?",String.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM skills WHERE name='ga-staging-test'",Integer.class));
        } finally {
            jdbc.update("DELETE FROM skill_staged_files WHERE staged_import_id=?",id);
            jdbc.update("DELETE FROM skill_staged_imports WHERE id=?",id);
        }
    }
    @Test void capabilitySkillNetworkFailureIsDurableAndReplayedWithoutAnotherAttempt() throws Exception {
        when(gitImporter.fetchTree(anyString(),isNull(),any())).thenThrow(new com.specagent.skill.importing.SkillImportException("Test network timeout"));
        var args=Map.<String,Object>of("url","https://example.org/ga-test.git");
        for(String capability:List.of("skill.import.discover","skill.import")) {
            recordTool(capability,capability,args);
            String wire=capabilityWire(capability,capability,args);
            var result=post("/capabilities",wire,properties.getInternalSecret());
            assertEquals(200,result.getStatusCode().value());
            var body=mapper.readTree(result.getBody());
            assertEquals("FAILED",body.path("status").asText());
            assertTrue(body.path("sourceRefs").isEmpty());
            assertEquals(result.getBody(),post("/capabilities",wire,properties.getInternalSecret()).getBody());
        }
        verify(gitImporter,times(2)).fetchTree(anyString(),isNull(),any());
        verify(gitImporter,never()).importHttps(anyString(),any(),any());
        assertEquals(2,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='TOOL_FAILED'",Integer.class,run));
    }
    @Test void capabilityMutationFailureRollsBackAndUnknownResultCannotProduceAnotherSideEffect() throws Exception {
        var args=Map.<String,Object>of("title","ga-native-test-"+thread);
        doAnswer(i -> { i.callRealMethod(); throw new IllegalStateException("Injected failure before host result commit"); })
                .when(capabilityRuntime).invokeApplicationScoped(anyString(),eq("project.create"),eq(run),anyMap());
        recordTool("rollback-create","project.create",args);
        var failed=post("/capabilities",capabilityWire("rollback-create","project.create",args),properties.getInternalSecret());
        assertEquals(409,failed.getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM projects WHERE title=?",Integer.class,args.get("title")));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ga_execution_calls WHERE run_id=? AND kind='TOOL' AND status='UNKNOWN'",Integer.class,run));
        recordTool("new-identity","project.create",args);
        var retry=post("/capabilities",capabilityWire("new-identity","project.create",args),properties.getInternalSecret());
        assertEquals(409,retry.getStatusCode().value());
        assertEquals("GA_CALL_IN_PROGRESS_OR_UNKNOWN",mapper.readTree(retry.getBody()).path("errorCode").asText());
        verify(capabilityRuntime,times(1)).invokeApplicationScoped(anyString(),eq("project.create"),eq(run),anyMap());
    }

    @Test void capabilitySkillDeadlineAfterExtractionRollsBackStagingAndFencesLateCommit() throws Exception {
        byte[] markdown="---\nname: ga-deadline-test\ndescription: Deadline fixture\n---\nDo nothing.\n".getBytes(StandardCharsets.UTF_8);
        var files=List.of(new com.specagent.skill.importing.SkillSourceFile("SKILL.md",markdown,
                com.specagent.skill.domain.SkillPackageFile.FileKind.SKILL_MD));
        when(gitImporter.fetchTree(anyString(),isNull(),any())).thenReturn(new com.specagent.skill.importing.GitSkillImporter.TreeInventory(
                "b".repeat(40),files,markdown.length,List.of()));
        doAnswer(i -> { Thread.sleep(800); return new com.specagent.skill.importing.GitSkillImporter.TreeInventory(
                "b".repeat(40),files,markdown.length,List.of()); }).when(gitImporter).fetchTree(anyString(),isNull(),any());
        var args=Map.<String,Object>of("url","https://example.org/ga-deadline-test.git");
        recordTool("deadline-stage","skill.import",args);
        jdbc.update("UPDATE ga_executions SET deadline=clock_timestamp()+interval '500 milliseconds' WHERE run_id=?",run);
        var response=post("/capabilities",capabilityWire("deadline-stage","skill.import",args),properties.getInternalSecret());
        assertEquals(409,response.getStatusCode().value());
        assertEquals("GA_EXECUTION_FENCE",mapper.readTree(response.getBody()).path("errorCode").asText());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM skill_staged_imports WHERE manifest=?",Integer.class,new String(markdown,StandardCharsets.UTF_8)));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ga_execution_calls WHERE run_id=? AND kind='TOOL' AND status='UNKNOWN'",Integer.class,run));
        verify(gitImporter,never()).importHttps(anyString(),isNull(),anyString());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='TOOL_COMPLETED'",Integer.class,run));
    }

    @Test void capabilityCancellationDoesNotWaitForNetworkPreparationOrCommitLateStaging() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        byte[] markdown = "---\nname: ga-cancel-fixture\ndescription: Fixture\n---\nNo execution.\n".getBytes(StandardCharsets.UTF_8);
        var files = List.of(new com.specagent.skill.importing.SkillSourceFile("SKILL.md",markdown,
                com.specagent.skill.domain.SkillPackageFile.FileKind.SKILL_MD));
        doAnswer(i -> {
            entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            return new com.specagent.skill.importing.GitSkillImporter.TreeInventory("d".repeat(40),files,markdown.length,List.of());
        }).when(gitImporter).fetchTree(anyString(),isNull(),any());
        var args = Map.<String,Object>of("url","https://example.org/ga-cancel-fixture.git");
        recordTool("cancel-stage","skill.import",args);
        var pending = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                post("/capabilities",capabilityWire("cancel-stage","skill.import",args),properties.getInternalSecret()));
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='TOOL_STARTED'",Integer.class,run));
            long start = System.nanoTime();
            var cancelled = http.postForEntity("http://localhost:"+port+"/api/v1/global-assistant/runs/"+run+"/cancel",null,String.class);
            assertEquals(200,cancelled.getStatusCode().value());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000,"Cancellation must not wait on clone transaction locks");
        } finally { release.countDown(); }
        assertEquals(409,pending.get(5,TimeUnit.SECONDS).getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM skill_staged_imports WHERE manifest=?",Integer.class,new String(markdown,StandardCharsets.UTF_8)));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ga_execution_calls WHERE run_id=? AND kind='TOOL' AND status='UNKNOWN'",Integer.class,run));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='TOOL_COMPLETED'",Integer.class,run));
    }

    @Test void capabilityLiveGitHttpsDiscoveryAndStagingWithActualJgit() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_GIT")));
        var skillProperties = com.specagent.skill.config.SkillProperties.defaults();
        var realGit = new com.specagent.skill.importing.GitSkillImporter(skillProperties,
                new com.specagent.common.network.OutboundNetworkPolicy());
        doAnswer(i -> realGit.fetchTree(i.getArgument(0),i.getArgument(1),i.getArgument(2)))
                .when(gitImporter).fetchTree(anyString(),any(),any());
        String url = Optional.ofNullable(System.getenv("SPEC_AGENT_GA_LIVE_GIT_URL")).orElse("https://github.com/obra/superpowers.git");
        String skill = Optional.ofNullable(System.getenv("SPEC_AGENT_GA_LIVE_GIT_SKILL")).orElse("skills/brainstorming");
        var discoverArgs = Map.<String,Object>of("url",url);
        recordTool("real-discover","skill.import.discover",discoverArgs);
        long started = System.nanoTime();
        var discovery = mapper.readTree(post("/capabilities",capabilityWire("real-discover","skill.import.discover",discoverArgs),properties.getInternalSecret()).getBody());
        assertEquals("SUCCEEDED",discovery.path("status").asText(),discovery.toString());
        String commit = discovery.path("content").path("commitSha").asText();
        assertTrue(commit.matches("[a-f0-9]{40}"));
        var stageArgs = Map.<String,Object>of("url",url,"skill",skill);
        recordTool("real-stage","skill.import",stageArgs);
        String wire = capabilityWire("real-stage","skill.import",stageArgs);
        var staged = post("/capabilities",wire,properties.getInternalSecret());
        var result = mapper.readTree(staged.getBody());
        assertEquals("SUCCEEDED",result.path("status").asText(),staged.getBody());
        UUID id = UUID.fromString(result.path("content").path("stagedImportId").asText());
        try {
            assertEquals(staged.getBody(),post("/capabilities",wire,properties.getInternalSecret()).getBody());
            assertEquals("STAGED",jdbc.queryForObject("SELECT status FROM skill_staged_imports WHERE id=?",String.class,id));
            assertTrue(jdbc.queryForObject("SELECT source_identity FROM skill_staged_imports WHERE id=?",String.class,id).startsWith("git:"));
            verify(gitImporter,times(2)).fetchTree(anyString(),any(),any());
            var evidence = new LinkedHashMap<String,Object>();
            evidence.put("recordedAt",Instant.now().toString()); evidence.put("boundary","actual HTTPS JGit + host RPC + test database; model not exercised");
            evidence.put("url",url); evidence.put("discoveryCommit",commit); evidence.put("skillPath",skill);
            evidence.put("stagingStatus","STAGED"); evidence.put("installedOrEnabled",false);
            evidence.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            evidence.put("productionEngineChanged",false);
            Files.writeString(Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_REAL_GIT_STAGING.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } finally {
            jdbc.update("DELETE FROM skill_staged_files WHERE staged_import_id=?",id);
            jdbc.update("DELETE FROM skill_staged_imports WHERE id=?",id);
        }
    }

    @Test void capabilityUserInputAndVerifiedNavigationAreHostResults() throws Exception {
        recordTool("c1","user-input.request",Map.of("question","请选择项目"));
        var question=post("/capabilities",capabilityWire("c1","user-input.request",Map.of("question","请选择项目")),properties.getInternalSecret());
        assertEquals("USER_INPUT_REQUIRED",mapper.readTree(question.getBody()).path("status").asText());
        recordTool("c2","ui.navigate",Map.of("destination","PROJECT","resourceId",UUID.randomUUID().toString()));
        // Different arguments here must fail origin validation before navigation.
        assertEquals(409,post("/capabilities",capabilityWire("c2","ui.navigate",Map.of("destination","PROJECT","resourceId",UUID.randomUUID().toString())),properties.getInternalSecret()).getStatusCode().value());
        recordTool("c3","ui.navigate",Map.of("destination","PROJECTS"));
        var navigation=post("/capabilities",capabilityWire("c3","ui.navigate",Map.of("destination","PROJECTS")),properties.getInternalSecret());
        assertEquals("SUCCEEDED",mapper.readTree(navigation.getBody()).path("status").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='UI_ACTION'",Integer.class,run));
    }

    @Test void capabilityUnknownMutationCannotBeRetriedUnderAnotherToolIdentity() throws Exception {
        var args=Map.<String,Object>of("title","ga-native-test-"+thread);
        recordTool("uncertain-c1","project.create",args);
        UUID oldCall=UUID.nameUUIDFromBytes((run+":uncertain-c1").getBytes(StandardCharsets.UTF_8));
        executions.reserve(scope,oldCall,"TOOL",Hashes.sha256Hex(capabilityWire("uncertain-c1","project.create",args)));
        executions.unknown(scope,oldCall);
        recordTool("uncertain-c2","project.create",args);
        var response=post("/capabilities",capabilityWire("uncertain-c2","project.create",args),properties.getInternalSecret());
        assertEquals(409,response.getStatusCode().value(),response.getBody());
        assertEquals("GA_CALL_IN_PROGRESS_OR_UNKNOWN",mapper.readTree(response.getBody()).path("errorCode").asText());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM projects WHERE title=?",Integer.class,args.get("title")));
        assertEquals(2,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
    }

    @Test void capabilityPythonFrameworkCallsActualBusinessRpcAndPersistsObservation() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_CROSS_LANGUAGE_TEST")));
        var counter=new java.util.concurrent.atomic.AtomicInteger();
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenAnswer(invocation ->
                counter.getAndIncrement()==0 ? new GaModelContract.Response(GaModelContract.VERSION,"",List.of(
                        new GaModelContract.ToolCall("business-c1","project_list_recent",Map.of("limit",1))),"tool_calls",new GaModelContract.Usage(5,2))
                        : new GaModelContract.Response(GaModelContract.VERSION,"rpc-complete",List.of(),"stop",new GaModelContract.Usage(5,2)));
        runPython("verify_ga_host_roundtrip.py",2);
        assertEquals(1,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM capability_invocations WHERE run_id=?",Integer.class,run));
    }

    private void recordTool(String id,String capability,Map<String,Object> arguments) throws Exception {
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenReturn(
                new GaModelContract.Response(GaModelContract.VERSION,"",List.of(new GaModelContract.ToolCall(id,
                        capability.replace('.','_'),arguments)),"tool_calls",new GaModelContract.Usage(3,2)));
        var model=new GaModelContract.Request(GaModelContract.VERSION,run,1,lease,UUID.randomUUID(),"AGENT",binding,
                request.messages(),request.tools(),"auto",1024,false);
        var response=post("/model-inference",mapper.writeValueAsString(model),properties.getInternalSecret());
        assertEquals(200,response.getStatusCode().value(),response.getBody());
    }
    private String capabilityWire(String id,String capability,Map<String,Object> arguments) {
        return catalogs.canonical(Maps.of("protocolVersion","ga-capability-invocation.v1","runId",run.toString(),"executionEpoch",1,
                "leaseId",lease.toString(),"toolCallId",id,"capabilityId",capability,"descriptorVersion","1",
                "catalogHash",businessCatalog.hash(),"arguments",arguments,"argumentsHash",Hashes.sha256Hex(catalogs.canonical(arguments))));
    }
    @Test void actualPythonFrameworkRoundtripsThroughHostHttpAndRestoresDatabaseCheckpoint() throws Exception {
        // Explicit so ordinary offline suites never require a locally installed Python environment.
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_CROSS_LANGUAGE_TEST")));
        runPython("verify_ga_host_roundtrip.py", 1);
    }

    @Test void liveNativeBrokerAndCheckpointRoundtripAgainstConfiguredHostModel() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_QUALIFICATION")));
        var target=configureLiveTransport();
        var evidence = Maps.of("recordedAt",Instant.now().toString(),"provider","OPENCODE_ZEN","model",target.selectedModel(),
                "scope","test-host authenticated Java/Python model+checkpoint RPC; real provider; synthetic tool result",
                "productionAcceptance","NOT_RUN","capabilityDispatch","NOT_RUN","retries",0);
        try {
            runPython("verify_ga_live_native_broker.py", 2);
            evidence.put("status","PASS"); evidence.put("providerCalls",2); evidence.put("checkpointRestored",true);
        } catch (Exception | AssertionError ex) {
            evidence.put("status","FAIL"); evidence.put("failureCategory",ex.getClass().getSimpleName());
            throw ex;
        } finally {
            Path output=Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_NATIVE_BROKER_INTEGRATION.json");
            Files.createDirectories(output.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),evidence);
        }
    }

    @Test void capabilityLiveFrameworkBusinessRpcAgainstConfiguredModel() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_QUALIFICATION")));
        var target=configureLiveTransport();
        var evidence=Maps.of("recordedAt",Instant.now().toString(),"provider","OPENCODE_ZEN","model",target.selectedModel(),
                "scope","create_agent + authenticated Java model/capability/checkpoint RPC; actual project.list_recent; test database",
                "productionAcceptance","NOT_RUN","retries",0);
        try {
            runPython("verify_ga_host_roundtrip.py", -1);
            int models=jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?",Integer.class,run);
            int tools=jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run);
            assertTrue(models>=2 && models<=6); assertTrue(tools>=1 && tools<=5);
            evidence.put("status","PASS"); evidence.put("providerCalls",models); evidence.put("toolCalls",tools);
            evidence.put("checkpointRestored",true);
        } catch (Exception | AssertionError ex) {
            evidence.put("status","FAIL"); evidence.put("failureCategory",ex.getClass().getSimpleName()); throw ex;
        } finally {
            Path output=Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_FRAMEWORK_BUSINESS_INTEGRATION.json");
            Files.createDirectories(output.getParent()); mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),evidence);
        }
    }

    private OpenCodeSettings configureLiveTransport() throws Exception {
        // Read production model settings only; all execution/checkpoint writes stay in the test DB.
        var hostDb = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                System.getenv().getOrDefault("SPEC_AGENT_GA_QUALIFICATION_DB_URL","jdbc:postgresql://localhost:5434/spec_agent"),
                System.getenv().getOrDefault("SPEC_AGENT_DB_USER","spec_agent"),
                System.getenv().getOrDefault("SPEC_AGENT_DB_PASSWORD","spec_agent_dev"));
        var hostJdbc = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(hostDb);
        var active = new com.specagent.modelsettings.ModelProviderSettingsService(
                new com.specagent.modelsettings.JdbcModelProviderSettingsRepository(hostJdbc));
        assertEquals(com.specagent.model.contract.ModelProvider.OPENCODE_ZEN, active.activeProvider());
        Path keyFile=Path.of(System.getenv().getOrDefault("SPEC_AGENT_GA_QUALIFICATION_KEY_FILE","data/secret-master.key"));
        String key=System.getenv().getOrDefault("SPEC_AGENT_SECRET_MASTER_KEY","");
        assertTrue(!key.isBlank() || Files.isRegularFile(keyFile), "Existing credential key required");
        var crypto = new com.specagent.modelsettings.ModelCredentialCrypto(key,"",keyFile.toString());
        var target = new com.specagent.modelsettings.JdbcOpenCodeSettingsRepository(hostJdbc,crypto).find().orElseThrow();
        when(settings.find()).thenReturn(Optional.of(target));
        // Prepare this isolated test binding before the first model call. Product rebinding is forbidden.
        jdbc.update("UPDATE ga_executions SET model=?,settings_revision=? WHERE run_id=?",target.selectedModel(),
                "opencode-settings:"+target.updatedAt(),run);
        var real = new com.specagent.model.provider.HttpOpenCodeZenTransport(mapper,OpenCodeZenTransport.BASE_URL,45,
                System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_PROXY","DIRECT"));
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenAnswer(invocation ->
                real.completeNativeGa(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),
                        invocation.getArgument(3),invocation.getArgument(4),invocation.getArgument(5)));
        return target;
    }

    private void runPython(String script, int expectedCalls) throws Exception {
        var descriptor=Map.of("capabilityId","project.search","version","1","name","project_search",
                "description","Find projects","inputSchema",Map.of("query",Map.of("type","string","required",true)),
                "readOnly",true,"sideEffectClass","NONE");
        String sortedDescriptor="[{\"capabilityId\":\"project.search\",\"description\":\"Find projects\",\"inputSchema\":{\"query\":{\"required\":true,\"type\":\"string\"}},\"name\":\"project_search\",\"readOnly\":true,\"sideEffectClass\":\"NONE\",\"version\":\"1\"}]";
        var execution=Maps.of("protocolVersion","ga-execution.v1","engineVersion","langchain-ga.v1",
                "threadId",thread.toString(),"runId",run.toString(),"executionEpoch",1,"leaseId",lease.toString(),
                "messageId",UUID.randomUUID().toString(),"content","查找我的项目","modelBindingId",binding.toString(),
                "policyVersion","1","catalogHash",Hashes.sha256Hex(sortedDescriptor),"historyBoundary",null,
                "history",List.of(),"uiContext",Map.of("currentPage","PROJECTS"),"structuredRefs",List.of(),
                "capabilities",List.of(descriptor),"budget",Map.of("maxModelCalls",6,"maxToolCalls",5,"maxDurationSeconds",180));
        if (businessCatalog!=null) { execution.put("capabilities",businessCatalog.descriptors()); execution.put("catalogHash",businessCatalog.hash()); }
        if (expectedCalls<0) execution.put("content","请使用 project.list_recent（limit=1）查看最近项目，只调用一次。用一句中文报告查询结果。");
        Path python=Path.of(System.getenv().getOrDefault("SPEC_AGENT_GA_TEST_PYTHON", "../agent-brain/.venv/Scripts/python.exe")).toAbsolutePath();
        assertTrue(Files.isRegularFile(python),"Configured Python environment is required");
        ProcessBuilder builder=new ProcessBuilder(python.toString(),"scripts/"+script);
        builder.directory(Path.of("../agent-brain").toFile());
        builder.environment().put("SPEC_AGENT_GA_TEST_HOST","http://127.0.0.1:"+port);
        builder.environment().put("SPEC_AGENT_GA_TEST_INTERNAL_TOKEN",properties.getInternalSecret());
        builder.environment().put("PYTHONFAULTHANDLER","1");
        if (expectedCalls<0) {
            builder.environment().put("SPEC_AGENT_GA_TEST_CAPABILITIES","true");
            builder.environment().put("SPEC_AGENT_GA_TEST_LIVE","true");
        }
        builder.redirectErrorStream(true);
        Path processOutput=Files.createTempFile("ga-python-rpc-", ".log");
        builder.redirectOutput(processOutput.toFile());
        Process process=builder.start();
        try {
            process.getOutputStream().write(catalogs.canonical(execution).getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
            assertTrue(process.waitFor(115,TimeUnit.SECONDS),"Cross-language test deadline");
            String output=Files.readString(processOutput,StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains("\"status\": \"PASS\""),output);
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM ga_checkpoints WHERE thread_id=?",Integer.class,thread)>0);
            if (expectedCalls>=0) verify(transport,times(expectedCalls)).completeNativeGa(any(),any(),any(),any(),any(),any());
        } finally { process.destroyForcibly(); Files.deleteIfExists(processOutput); }
    }
    private ResponseEntity<String> post(String path,String body,String token) {
        var headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Spec-Agent-Internal-Token",token);
        return http.postForEntity("/internal/v1/global-assistant"+path,new HttpEntity<>(body,headers),String.class);
    }
}
