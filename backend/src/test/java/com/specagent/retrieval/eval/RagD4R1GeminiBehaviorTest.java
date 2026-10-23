package com.specagent.retrieval.eval;

import com.fasterxml.jackson.databind.*;
import com.specagent.agent.broker.*;
import com.specagent.agent.decision.*;
import com.specagent.agent.protocol.*;
import com.specagent.agent.runtime.*;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.model.contract.*;
import com.specagent.model.provider.CustomInferenceGateway;
import com.specagent.modelsettings.*;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.NodeService;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit, bounded real-model observation. Never selected by ordinary CI. */
@Tag("external-service")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="SPEC_AGENT_GEMINI_R1_BEHAVIOR",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spec.agent.brain.engine=remote-python", "spec.agent.model.inference=opencode",
    "logging.level.org.springframework.jdbc=WARN", "logging.level.org.flywaydb=WARN"})
@ActiveProfiles("test")
class RagD4R1GeminiBehaviorTest extends com.specagent.testing.SharedPythonRetrievalFixture {
    static {
        if("true".equals(System.getenv("SPEC_AGENT_GEMINI_R1_BEHAVIOR"))) {
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
    @SpyBean RemotePythonDecisionEngine engine;
    @SpyBean CustomInferenceGateway gateway;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Path root=Path.of("..").toAbsolutePath().normalize();
    final Path output=root.resolve("scratch/rag-d4-r1/gemini-first-observation");
    final AtomicInteger requests=new AtomicInteger();
    Map<String,Object> observation;
    AgentRequestEnvelope captured;
    String actualBrain;

    void write(String file,Object value) throws Exception {
        Files.writeString(output.resolve(file),json.writerWithDefaultPrettyPrinter().writeValueAsString(value),
            StandardOpenOption.CREATE_NEW);
    }
    void append(String file,Object value) throws Exception {
        Files.writeString(output.resolve(file),json.writeValueAsString(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    @Test void frozenFirstObservationsThroughExistingRuntimeAndBroker() throws Exception {
        Path cases=root.resolve("contracts/retrieval/evaluation/rag-d4-r1-behavior.v1.json");
        byte[] bytes=Files.readAllBytes(cases);
        var dataset=json.readTree(bytes);
        String datasetHash=com.specagent.common.Hashes.sha256Hex(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        String promptHash=RetrievalEvaluationSnapshot.fileSha(root.resolve("agent-brain/src/spec_agent_brain/prompts/decision.py"));
        Set<String> completed=new HashSet<>();
        if(Files.exists(output)) {
            assertThat(System.getenv("SPEC_AGENT_GEMINI_R1_RESUME_UNOBSERVED")).as("explicit resume of only never-observed cases").isEqualTo("true");
            assertThat(Files.exists(output.resolve("execution.json"))).as("completed observation cannot be resumed").isFalse();
            var freeze=json.readTree(Files.readString(output.resolve("freeze.json")));
            assertThat(freeze.path("datasetSha256").asText()).isEqualTo(datasetHash);
            assertThat(freeze.path("promptSha256").asText()).isEqualTo(promptHash);
            for(String line:Files.readAllLines(output.resolve("results.jsonl"))) completed.add(json.readTree(line).path("id").asText());
            for(String line:Files.readAllLines(output.resolve("requests.jsonl"))) {
                var record=json.readTree(line); requests.set(Math.max(requests.get(),record.path("ordinal").asInt()));
                if(record.has("case")) assertThat(completed).as("a dispatched observation must never be retried").contains(record.path("case").asText());
            }
        } else {
            Files.createDirectories(output);
            write("freeze.json",Map.of("recordedAt",Instant.now().toString(),"review",dataset.path("review").asText(),
            "datasetSha256",com.specagent.common.Hashes.sha256Hex(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)),
            "promptSha256",promptHash,
            "scenarioCount",dataset.path("scenarios").size(),"maxUnderlyingRequests",30,"retries",0));
        }
        actualBrain=properties.getBaseUrl();
        ModelProvider originalProvider=providers.activeProvider();
        if(settings.status().configured()) {
            assertThat(settings.status().hasKey()).as("no existing credential may be replaced").isFalse();
            assertThat(settings.status().displayName()).isEqualTo("D4 R1 cleared");
        }
        try {
            String desktop=System.getenv("SPEC_AGENT_GEMINI_R1_DESKTOP");
            Path keyFile=(desktop==null?Path.of(System.getProperty("user.home"),"Desktop"):Path.of(desktop)).resolve("key2.txt");
            String key=Files.readString(keyFile).strip();
            assertThat(!key.isBlank() && !key.contains("\n") && !key.contains("\r") && key.length()<=16384).isTrue();
            settings.save("CHAT_COMPLETIONS","https://generativelanguage.googleapis.com/v1beta/openai",key,
                "gemini-3.5-flash-lite","MANUAL","D4 R1 isolated test");
            // One existing compatibility probe, counted BEFORE dispatch. No retries.
            int probe=requests.incrementAndGet();
            assertThat(probe).isLessThanOrEqualTo(30);
            append("requests.jsonl",Map.of("ordinal",probe,"kind","COMPATIBILITY_PROBE","state","DISPATCHING"));
            try { settings.validate(); }
            catch(RuntimeException failure) {
                append("requests.jsonl",Map.of("ordinal",probe,"state","FAILED","category",safeCategory(failure)));
                throw new AssertionError("Gemini compatibility probe failed; sanitized evidence recorded");
            }
            append("requests.jsonl",Map.of("ordinal",probe,"state","SUCCEEDED"));
            providers.setActiveProvider(ModelProvider.CUSTOM);
            doAnswer(call -> {
                int ordinal=requests.incrementAndGet();
                if(ordinal>30) throw new IllegalStateException("Gemini request budget exhausted before dispatch");
                append("requests.jsonl",Map.of("ordinal",ordinal,"case",observation.get("id"),"state","DISPATCHING"));
                long started=System.nanoTime();
                try {
                    var response=(ModelInferenceResponse)call.callRealMethod();
                    observation.put("rawModelOutput",response.content());
                    observation.put("promptTokens",response.promptTokens());
                    observation.put("completionTokens",response.completionTokens());
                    observation.put("modelMillis",(System.nanoTime()-started)/1_000_000);
                    append("requests.jsonl",Map.of("ordinal",ordinal,"state","SUCCEEDED"));
                    return response;
                } catch(RuntimeException failure) {
                    append("requests.jsonl",Map.of("ordinal",ordinal,"state","FAILED","category",safeCategory(failure)));
                    throw failure;
                }
            }).when(gateway).complete(any(ModelInferenceRequest.class),any(RuntimeCustomSettings.class));
            // The fixture starts Python after Spring's bean construction; resolve its ephemeral
            // address per invocation. All prompts, grants, parsing and validators stay production.
            doAnswer(call -> {
                captured=call.getArgument(0);
                observation.put("input",json.readTree(AgentContracts.write(captured)));
                properties.setBaseUrl(actualBrain);
                var remote=new RemotePythonDecisionEngine(properties); remote.setGrants(grants);
                var response=remote.runDecision(captured);
                observation.put("response",json.readTree(AgentContracts.write(response)));
                return response;
            }).when(engine).runDecision(any());
            for(var scenario:dataset.path("scenarios")) if(!completed.contains(scenario.path("id").asText())) observe(scenario);
            write("execution.json",Map.of("model","gemini-3.5-flash-lite","actualModelVersion","NOT_EXPOSED_BY_GATEWAY",
                "underlyingRequests",requests.get(),"retries",0,"streaming",false,
                "firstVisibleBodyMillis","NOT_MEASURED_NONSTREAMING", "globalAssistantNativeTools","UNSUPPORTED_CURRENT_CUSTOM_PATH"));
        } finally {
            properties.setBaseUrl(actualBrain);
            if(settings.status().configured()) settings.save("CHAT_COMPLETIONS",
                "https://generativelanguage.googleapis.com/v1beta/openai","","gemini-3.5-flash-lite","MANUAL","D4 R1 cleared");
            providers.setActiveProvider(originalProvider);
        }
    }
    void observe(JsonNode scenario) throws Exception {
        String id=scenario.path("id").asText();
        observation=new LinkedHashMap<>(); observation.put("id",id); captured=null;
        var project=createProject("D4 R1 "+id);
        var anchor=graph.createRootDraftNode(project.id(),project.activeRouteId(),"NOTE",Map.of("text","合成验收工作区"));
        var aliases=new LinkedHashMap<String,String>();
        for(var source:scenario.path("sources")) {
            var owner=source.path("foreign").asBoolean()?createProject("D4 R1 foreign "+id):project;
            var parent=owner.id().equals(project.id())?anchor:graph.createRootDraftNode(owner.id(),owner.activeRouteId(),"NOTE",Map.of("text","其他项目"));
            UUID tip=jdbc.queryForObject("SELECT tip_node_id FROM routes WHERE id=?",UUID.class,owner.activeRouteId());
            var resource=graph.attachResource(owner.id(),owner.activeRouteId(),tip,"TEXT",Map.of("text",source.path("text").asText()));
            projectResources(owner.id());
            aliases.put(source.path("alias").asText(),resource.id().toString());
            if(source.path("retracted").asBoolean()) nodes.setRetracted(resource.id(),true);
        }
        observation.put("sourceAliases",aliases);
        String before=graphIdentity(project.id());
        int previous=requests.get();
        long started=System.nanoTime();
        UUID run=runs.createQueuedNodeQuery(project.id(),project.activeRouteId(),anchor.id(),scenario.path("query").asText());
        try {
            if(scenario.path("retrievalOutage").asBoolean()) properties.setBaseUrl("http://127.0.0.1:1");
            worker.executeRun(runs.claimNodeQueryRun(run).orElseThrow());
        } catch(RuntimeException failure) { observation.put("failure",safeCategory(failure)); }
        finally {properties.setBaseUrl(actualBrain);}
        observation.put("totalMillis",(System.nanoTime()-started)/1_000_000);
        observation.put("modelCalls",requests.get()-previous);
        observation.put("runStatus",runs.getRun(run).orElseThrow().status().name());
        observation.put("graphUnchanged",before.equals(graphIdentity(project.id())));
        observation.put("toolRecords",jdbc.queryForList("SELECT event_type,payload::text AS payload FROM agent_run_events WHERE run_id=? ORDER BY sequence",run));
        if(captured!=null) {
            var context=contexts.findById(UUID.fromString(captured.snapshot().snapshotId())).orElseThrow();
            observation.put("frozenReplayIdentical",captured.snapshot().equals(snapshots.build(context)));
        }
        append("results.jsonl",observation);
        // No retry or prompt change on a failed observation.
    }
    String graphIdentity(UUID project) {
        return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(n) ORDER BY n.id)::text,'[]') FROM nodes n WHERE project_id=?",String.class,project)
            +jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY a.id)::text,'[]') FROM answers a WHERE project_id=?",String.class,project)
            +jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY p.id)::text,'[]') FROM answer_patches p WHERE project_id=?",String.class,project)
            +jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id)::text,'[]') FROM routes r WHERE project_id=?",String.class,project);
    }
    String safeCategory(RuntimeException failure) {
        return failure instanceof ModelGatewayException gatewayFailure?gatewayFailure.gatewayCategory().name():failure.getClass().getSimpleName();
    }
}
