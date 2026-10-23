package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.*;
import com.specagent.agent.broker.*;
import com.specagent.agent.decision.*;
import com.specagent.agent.protocol.*;
import com.specagent.agent.runtime.*;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.assistant.runtime.*;
import com.specagent.assistant.conversation.*;
import com.specagent.assistant.tool.GaCatalogProjection;
import com.specagent.model.contract.*;
import com.specagent.model.provider.*;
import com.specagent.modelsettings.*;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit first observation. Browser submits the actual query; no scripted model/tool replies. */
@Tag("external-service")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="SPEC_AGENT_GEMINI_R2_LIVE",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spec.agent.brain.engine=remote-python","spec.agent.model.inference=opencode",
    "spec.global-assistant.ga-native.custom-qualified-targets[0].base-url=https://generativelanguage.googleapis.com/v1beta/openai",
    "spec.global-assistant.ga-native.custom-qualified-targets[0].model=gemini-3.5-flash-lite",
    "logging.level.org.springframework.jdbc=WARN","logging.level.org.flywaydb=WARN"})
@ActiveProfiles("test")
class RagD4R2LiveDeliveryTest extends com.specagent.testing.SharedPythonRetrievalFixture {
    static {
        if("true".equals(System.getenv("SPEC_AGENT_GEMINI_R2_LIVE"))) {
            System.setProperty("https.proxyHost","127.0.0.1");
            System.setProperty("https.proxyPort","7897");
            System.setProperty("http.nonProxyHosts","localhost|127.0.0.1");
        }
    }
    @Autowired CustomProviderSettingsService settings;
    @Autowired ModelProviderSettingsService providers;
    @Autowired AgentBrainProperties properties;
    @Autowired WorkspaceRpcGrants grants;
    @Autowired GraphCommandService graph;
    @Autowired NodeService nodes;
    @Autowired RunService runs;
    @Autowired RunWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentInputSnapshotBuilder snapshots;
    @Autowired ContextSnapshotRepository contexts;
    @Autowired TestRestTemplate http;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired ConversationDeleteService conversationDelete;
    @Autowired GaExecutionStore executions;
    @Autowired GaCatalogProjection catalogs;
    @Autowired GaExecutionModelTargetResolver targets;
    @Autowired com.specagent.retrieval.protocol.RetrievalStore store;
    @Autowired com.specagent.retrieval.protocol.SharedRetrievalHost retrieval;
    @LocalServerPort int port;
    @SpyBean RemotePythonDecisionEngine engine;
    @SpyBean CustomInferenceGateway gateway;
    @SpyBean ChatCompletionsGaTextTransport transport;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Path root=Path.of("..").toAbsolutePath().normalize();
    final Path output=root.resolve("scratch/rag-d4-r2/first-observation");
    final AtomicInteger requests=new AtomicInteger();
    final Map<String,Object> projectObservation=new LinkedHashMap<>();
    AgentRequestEnvelope captured;
    @Override protected String embeddingUrl() {return "http://127.0.0.1:11434";}
    @Override protected Path processLogDirectory(Path root) {return root.resolve("scratch/rag-d4-r2");}
    synchronized void write(String file,Object value) throws Exception {
        Files.writeString(output.resolve(file),json.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardOpenOption.CREATE_NEW);
    }
    synchronized void append(Object value) throws Exception {
        Files.writeString(output.resolve("requests.jsonl"),json.writeValueAsString(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    int dispatch(String kind) throws Exception {
        int n=requests.incrementAndGet();
        if(n>12) throw new IllegalStateException("Underlying request budget exceeded before dispatch");
        append(Map.of("ordinal",n,"kind",kind,"state","DISPATCHING","at",Instant.now().toString()));return n;
    }
    void result(int n,long started,Object response) throws Exception {
        append(Map.of("ordinal",n,"state","SUCCEEDED","millis",(System.nanoTime()-started)/1_000_000,"response",response));
    }
    String category(Throwable e) {return e instanceof ModelGatewayException m?m.gatewayCategory().name():e.getClass().getSimpleName();}
    void waitFor(String file) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(12);
        while(!Files.exists(output.resolve(file)) && System.nanoTime()<deadline) Thread.sleep(250);
        assertThat(Files.exists(output.resolve(file))).as("Browser acceptance signal: "+file).isTrue();
    }
    @Test void firstDeliveryObservation() throws Exception {
        assertThat(Files.exists(output)).as("Never overwrite or retry first observation").isFalse();
        Files.createDirectories(output);
        String input=Files.readString(root.resolve("contracts/retrieval/evaluation/rag-d4-r2-delivery.v1.json"));
        var data=json.readTree(input);
        write("freeze.json",Map.of("recordedAt",Instant.now().toString(),"input",data,
            "datasetSha256",com.specagent.common.Hashes.sha256Hex(input),"retries",0,"maxUnderlyingRequests",12));
        ModelProvider original=providers.activeProvider();
        String actualBrain=properties.getBaseUrl();
        assertThat(settings.status().hasKey()).as("Do not replace an existing credential").isFalse();
        try {
            String key=Files.readString(Path.of(System.getProperty("user.home"),"Desktop","key2.txt")).strip();
            assertThat(!key.isBlank() && !key.contains("\n") && !key.contains("\r") && key.length()<=16384).isTrue();
            settings.save("CHAT_COMPLETIONS","https://generativelanguage.googleapis.com/v1beta/openai",key,
                data.path("model").asText(),"MANUAL","D4 R2 isolated test");
            int n=dispatch("COMPATIBILITY_PROBE");long start=System.nanoTime();
            try {settings.validate();result(n,start,Map.of("validated",true,"tokens","NOT_EXPOSED_BY_PROBE"));}
            catch(RuntimeException e) {append(Map.of("ordinal",n,"state","FAILED","category",category(e)));throw new AssertionError("Compatibility probe failed; sanitized evidence retained");}
            providers.setActiveProvider(ModelProvider.CUSTOM);
            doAnswer(call->{
                int ordinal=dispatch("PROJECT_QUERY");long started=System.nanoTime();
                try {var response=(ModelInferenceResponse)call.callRealMethod();result(ordinal,started,response);return response;}
                catch(RuntimeException e) {append(Map.of("ordinal",ordinal,"state","FAILED","category",category(e)));throw e;}
            }).when(gateway).complete(any(ModelInferenceRequest.class),any(RuntimeCustomSettings.class));
            doAnswer(call->{
                int ordinal=dispatch("GA_NATIVE");long started=System.nanoTime();
                append(Map.of("ordinal",ordinal,"request",call.getArgument(1,GaModelContract.Request.class)));
                try {var response=(GaModelContract.Response)call.callRealMethod();result(ordinal,started,response);return response;}
                catch(RuntimeException e) {
                    var failed=new LinkedHashMap<String,Object>();failed.put("ordinal",ordinal);failed.put("state","FAILED");
                    failed.put("category",category(e));failed.put("millis",(System.nanoTime()-started)/1_000_000);
                    if(e instanceof ModelGatewayException m) failed.put("httpStatus",m.httpStatus());
                    append(failed);throw e;
                }
            }).when(transport).complete(any(),any(),any(),any());
            doAnswer(call->{
                captured=call.getArgument(0);projectObservation.put("input",json.readTree(AgentContracts.write(captured)));
                properties.setBaseUrl(actualBrain);
                var remote=new RemotePythonDecisionEngine(properties);remote.setGrants(grants);
                var response=remote.runDecision(captured);
                projectObservation.put("response",json.readTree(AgentContracts.write(response)));return response;
            }).when(engine).runDecision(any());
            var project=createProject(data.path("projectTitle").asText());
            var anchor=graph.createRootDraftNode(project.id(),project.activeRouteId(),"NOTE",Map.of("text",data.path("anchor").asText()));
            var resource=nodes.createFloatingWorkspaceNode(project.id(),NodeKind.RESOURCE,"TEXT",Map.of("text",data.path("resource").asText()),NodeAuthorKind.USER,null);
            projectResources(project.id());
            UUID generation=store.ensureProjectGeneration(project.id());int indexed=0;
            while(retrieval.indexOneBatch(project.id(),generation)) assertThat(++indexed).isLessThan(64);
            assertThat(indexed).as("Normal Ollama vector index").isPositive();
            write("vector-index.json",Map.of("batches",indexed,"embeddingModel","qwen3-embedding:0.6b","profileId","26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047"));
            // Qualify the native tool protocol independently; failure never triggers a model retry.
            try {observeGa(project.id(),data);}
            catch(Throwable failure) {write("ga-failure.json",Map.of("category",category(failure),"nativeToolSupport","FIRST_OBSERVATION_FAILED"));}
            write("browser-ready.json",Map.of("apiOrigin","http://127.0.0.1:"+port,"projectId",project.id(),
                "anchorId",anchor.id(),"resourceId",resource.id(),"query",data.path("query").asText()));
            UUID run=null;long until=System.nanoTime()+TimeUnit.MINUTES.toNanos(12);
            while(run==null && System.nanoTime()<until) {
                var found=jdbc.queryForList("SELECT id FROM agent_runs WHERE project_id=? AND trigger_type='node_query' AND status='created'",UUID.class,project.id());
                if(!found.isEmpty()) run=found.getFirst();else Thread.sleep(250);
            }
            assertThat(run).as("Actual browser-submitted query").isNotNull();
            long started=System.nanoTime();
            try {worker.executeRun(runs.claimNodeQueryRun(run).orElseThrow());}
            finally {projectObservation.put("totalMillis",(System.nanoTime()-started)/1_000_000);projectObservation.put("runStatus",runs.getRun(run).orElseThrow().status());write("project.json",projectObservation);}
            String url="/api/v1/projects/"+project.id()+"/nodes/"+anchor.id()+"/query/"+run;
            var api=http.getForEntity(url,String.class);write("api-available.json",json.readTree(api.getBody()));
            assertThat(api.getStatusCode().value()).isEqualTo(200);
            var body=json.readTree(api.getBody());assertThat(body.path("sourceStatus").asText()).isEqualTo("AVAILABLE");
            for(var fact:data.path("requiredFacts")) assertThat(body.path("message").asText()).contains(fact.asText());
            assertThat(body.path("resourceRefs").toString()).contains(resource.id().toString());
            assertThat(captured.snapshot().metadata().retrieval().vectorUnavailable()).isFalse();
            var context=contexts.findById(UUID.fromString(captured.snapshot().snapshotId())).orElseThrow();
            assertThat(snapshots.build(context)).isEqualTo(captured.snapshot());
            String audit=jdbc.queryForObject("SELECT payload::text FROM agent_run_events WHERE run_id=? AND event_type='RESPOND_MESSAGE'",String.class,run);
            assertThat(json.readTree(audit).path("sourceRefs").toString()).contains("resource-chunk:"+resource.id());
            waitFor("browser-a-complete.json");
            nodes.setRetracted(resource.id(),true);
            var withdrawn=json.readTree(http.getForEntity(url,String.class).getBody());write("api-withdrawn.json",withdrawn);
            assertThat(withdrawn.path("sourceStatus").asText()).isEqualTo("UNAVAILABLE");
            assertThat(withdrawn.path("message").isNull()).isTrue();assertThat(withdrawn.path("resourceRefs").isEmpty()).isTrue();
            assertThat(jdbc.queryForObject("SELECT payload::text FROM agent_run_events WHERE run_id=? AND event_type='RESPOND_MESSAGE'",String.class,run)).isEqualTo(audit);
            assertThat(snapshots.build(context)).isEqualTo(captured.snapshot());
            write("withdrawn-ready.json",Map.of("auditUnchanged",true,"frozenReplayIdentical",true));
            waitFor("browser-b-complete.json");
            write("execution.json",Map.of("underlyingRequests",requests.get(),"retries",0,"provider","Gemini","model",data.path("model").asText(),
                "billingTier","verified free tier","actualCharge","NOT_EXPOSED_BY_API","expectedCharge",0,"currency","USD"));
        } finally {
            settings.save("CHAT_COMPLETIONS","https://generativelanguage.googleapis.com/v1beta/openai","",data.path("model").asText(),"MANUAL","D4 R2 cleared");
            providers.setActiveProvider(original);properties.setBaseUrl(actualBrain);
        }
    }
    void observeGa(UUID project,JsonNode data) throws Exception {
        UUID thread=conversations.createThread().id(),run=null;
        try {
            var catalog=catalogs.snapshot(catalogs.current().descriptors().stream().filter(d->d.capabilityId().equals("project.get_summary")).toList());
            assertThat(catalog.descriptors()).hasSize(1);
            run=conversations.createRun(thread,"ga.v1","ga.v1",catalog.hash()).id();
            UUID lease=UUID.randomUUID(),binding=UUID.randomUUID();
            var ready=(GaExecutionModelTargetResolver.Ready)targets.resolveActive();
            assertThat(ready.mode()).isEqualTo(GaExecutionMode.NATIVE_TOOLS);
            var target=ready.target();
            executions.initialize(new GaExecutionStore.Scope(run,1,lease),new GaExecutionStore.Binding(thread,binding,target.provider(),target.model(),
                target.settingsRevision(),Instant.now().plusSeconds(120),catalog.tools()),catalog.descriptors());
            jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?",run);
            var request=com.specagent.common.Maps.of("protocolVersion","ga-execution.v1","engineVersion","langchain-ga.v1",
                "threadId",thread.toString(),"runId",run.toString(),"executionEpoch",1,"leaseId",lease.toString(),"messageId",UUID.randomUUID().toString(),
                "content",data.path("globalAssistant").path("queryTemplate").asText().replace("{projectId}",project.toString()),"modelBindingId",binding.toString(),
                "policyVersion","1","catalogHash",catalog.hash(),"historyBoundary",null,"history",List.of(),"uiContext",Map.of("currentPage","PROJECT","selectedEntity",project.toString()),
                "structuredRefs",List.of(),"capabilities",catalog.descriptors(),"budget",Map.of("maxModelCalls",4,"maxToolCalls",2,"maxDurationSeconds",120));
            write("ga-input.json",request);
            var builder=new ProcessBuilder(System.getenv("SPEC_AGENT_GA_TEST_PYTHON"),"scripts/verify_ga_r2_delivery.py");
            builder.directory(root.resolve("agent-brain").toFile());
            builder.environment().put("SPEC_AGENT_GA_TEST_HOST","http://127.0.0.1:"+port);
            builder.environment().put("SPEC_AGENT_GA_TEST_INTERNAL_TOKEN",properties.getInternalSecret());
            builder.environment().put("SPEC_AGENT_R2_GA_OUTPUT",output.resolve("ga-observation.json").toString());
            builder.redirectErrorStream(true).redirectOutput(output.resolve("ga-python.log").toFile());
            var process=builder.start();
            try {
                process.getOutputStream().write(json.writeValueAsBytes(request));process.getOutputStream().close();
                assertThat(process.waitFor(150,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
                var observed=json.readTree(Files.readString(output.resolve("ga-observation.json")));
                assertThat(observed.path("messages").get(observed.path("messages").size()-1).path("content").asText()).contains(data.path("projectTitle").asText());
            } finally {process.destroyForcibly();}
        } finally {
            if(run!=null) {
                write("ga-host.json",Map.of("execution",jdbc.queryForList("SELECT model_calls,tool_calls FROM ga_executions WHERE run_id=?",run),
                    "invocations",jdbc.queryForList("SELECT capability_id,status FROM capability_invocations WHERE run_id=?",run)));
                jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?",run);
            }
            conversationDelete.deleteThread(thread);
        }
    }
}
