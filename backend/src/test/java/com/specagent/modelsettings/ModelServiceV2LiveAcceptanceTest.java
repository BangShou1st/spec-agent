package com.specagent.modelsettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.access.ActorContext;
import com.specagent.access.ActorContextHolder;
import com.specagent.access.OrgRole;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.assistant.conversation.ConversationDeleteService;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.runtime.GaExecutionMode;
import com.specagent.assistant.runtime.GaExecutionModelTargetResolver;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.assistant.tool.GaCatalogProjection;
import com.specagent.eval.EvalProbeCapabilities;
import com.specagent.eval.GivenSpec;
import com.specagent.eval.GraphStep;
import com.specagent.eval.ScenarioDefinition;
import com.specagent.eval.ScenarioRunner;
import com.specagent.eval.UserEvent;
import com.specagent.eval.VariantSpec;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.provider.ChatCompletionsGaTextTransport;
import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.CustomInferenceGateway;
import com.specagent.workspace.project.ProjectDeletionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Model Service V2-Live: real free Gemini model configured as an ordinary Custom
 * connection, exercised through the production Java/Python chain.
 *
 * <p>This suite is opt-in and never part of CI. It proves exactly these layers,
 * and reports them separately: CHAT / STRUCTURED / NATIVE_TOOLS capability
 * qualification, one real project-Agent answer cycle through the Python brain
 * and the Java broker, and one real Global Assistant native tool round trip.
 * It does not claim a paid charge of zero; the provider publishes no pricing.
 */
@Tag("external-service")
@EnabledIfEnvironmentVariable(named = "SPEC_AGENT_MODEL_V2_LIVE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18086",
        "spec.agent.brain.engine=remote-python",
        "spec.agent.brain.base-url=http://127.0.0.1:18120",
        "spec.agent.model.inference=opencode",
        "spec.agent.retrieval.engine=java-hybrid.v1",
        "logging.level.org.springframework.jdbc=WARN",
        "logging.level.org.flywaydb=WARN"
})
@Import(EvalProbeCapabilities.Config.class)
@ActiveProfiles("test")
class ModelServiceV2LiveAcceptanceTest {

    static {
        // This workstation reaches the public provider only through its local proxy;
        // loopback (Spring server and the Python brain) must stay direct.
        if ("true".equals(System.getenv("SPEC_AGENT_MODEL_V2_LIVE"))) {
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", "7897");
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", "7897");
            System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
        }
    }

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String PRIMARY_MODEL = "gemini-3.5-flash-lite";
    private static final String REPLACEMENT_MODEL = "gemini-flash-lite-latest";
    private static final int MAX_REQUESTS = 12;
    private static final int BRAIN_PORT = 18120;

    private static Process brainProcess;
    private static Path evidenceDir;

    @Autowired ModelProvidersService connections;
    @Autowired ModelConnectionCapabilities capabilities;
    @Autowired ModelProviderSettingsService activeProviders;
    @Autowired ScenarioRunner scenarioRunner;
    @Autowired GaCatalogProjection catalogs;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired ConversationDeleteService conversationDelete;
    @Autowired GaExecutionStore executions;
    @Autowired GaExecutionModelTargetResolver targets;
    @Autowired ProjectDeletionService projects;
    @Autowired IdentityRepository identities;
    @Autowired AgentBrainProperties brain;
    @Autowired JdbcTemplate jdbc;

    @SpyBean CompatibilityProbeService probes;
    @SpyBean CustomInferenceGateway customGateway;
    @SpyBean ChatCompletionsGaTextTransport gaTransport;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Map<String, Integer> dispatched = new LinkedHashMap<>();
    private final AtomicInteger gaPromptTokens = new AtomicInteger();
    private final AtomicInteger gaCompletionTokens = new AtomicInteger();
    private UUID org;
    private UUID user;
    private UUID liveProject;
    private ActorContextHolder.Scope actorScope;

    // ---------------------------------------------------------------- fixture

    @BeforeAll
    static void startBrain() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        evidenceDir = root.resolve(System.getenv().getOrDefault(
                "SPEC_AGENT_LIVE_EVIDENCE_DIR", "scratch/model-service-v2-live"));
        Files.createDirectories(evidenceDir);
        Path log = Files.createTempFile(evidenceDir, "brain-", ".log");
        ProcessBuilder builder = new ProcessBuilder(com.specagent.testing.PythonExecutable.locate(root),
                "-m", "uvicorn", "spec_agent_brain.app:create_app", "--factory",
                "--host", "127.0.0.1", "--port", Integer.toString(BRAIN_PORT));
        builder.directory(root.resolve("agent-brain").toFile());
        builder.environment().put("SPEC_AGENT_BRAIN_MODEL_MODE", "broker");
        builder.environment().put("SPEC_AGENT_INTERNAL_BROKER_URL",
                "http://127.0.0.1:18086/internal/v1/model-inference");
        builder.environment().put("SPEC_AGENT_BRAIN_INTERNAL_SECRET", "dev-internal-secret");
        builder.environment().put("SPEC_AGENT_OLLAMA_URL", "http://127.0.0.1:1");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        brainProcess = builder.start();
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < until && brainProcess.isAlive()) {
                try {
                    int status = http.send(java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:" + BRAIN_PORT + "/health"))
                            .timeout(java.time.Duration.ofSeconds(1)).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (status == 200) {
                        return;
                    }
                } catch (Exception unavailable) {
                    // still booting
                }
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("agent-brain did not become ready on port " + BRAIN_PORT);
    }

    @AfterAll
    static void stopBrain() {
        if (brainProcess != null) {
            brainProcess.descendants().forEach(ProcessHandle::destroyForcibly);
            brainProcess.destroyForcibly();
        }
    }

    @BeforeEach
    void actor() {
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        identities.insertUserIfAbsent(user, "Model V2 live owner");
        identities.insertOrganizationIfAbsent(org, "Model V2 live org", "STANDARD");
        identities.upsertMembership(org, user, OrgRole.OWNER);
        actorScope = ActorContextHolder.bind(ActorContext.oidc(user, org));

        doAnswer(call -> dispatch("CHAT", call)).when(probes).probeChat(any(), any(), any(), any());
        doAnswer(call -> dispatch("STRUCTURED", call)).when(probes).probeCustom(any(), any(), any(), any());
        doAnswer(call -> dispatch("NATIVE_ROUND_TRIP", call)).when(probes).probeNativeTools(any(), any(), any());
        doAnswer(call -> dispatch("PROJECT_AGENT", call))
                .when(customGateway).complete(any(com.specagent.model.contract.ModelInferenceRequest.class),
                        any(com.specagent.model.contract.RuntimeCustomSettings.class));
        doAnswer(call -> {
            Object response = dispatch("GA_NATIVE", call);
            if (response instanceof GaModelContract.Response typed && typed.usage() != null) {
                gaPromptTokens.addAndGet(typed.usage().promptTokens());
                gaCompletionTokens.addAndGet(typed.usage().completionTokens());
            }
            return response;
        }).when(gaTransport).complete(any(), any(), any(), any());
    }

    @AfterEach
    void cleanup() {
        try {
            if (liveProject != null) {
                projects.deleteProject(liveProject);
                liveProject = null;
            }
        } finally {
            if (actorScope != null) {
                actorScope.close();
                actorScope = null;
            }
            ActorContextHolder.clear();
        }
    }

    // ------------------------------------------------------------- acceptance

    @Test
    void realFreeGeminiCustomConnectionDrivesBothAgentsEndToEnd() throws Exception {
        String attestation = System.getenv("SPEC_AGENT_LIVE_COST_ATTESTATION");
        assertThat(attestation)
                .as("Providers without catalog pricing require an explicit owner free-tier attestation")
                .isNotBlank();
        String key = readKey();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recordedAt", Instant.now().toString());
        report.put("provider", "Gemini (OpenAI-compatible endpoint)");
        report.put("baseUrl", BASE_URL);
        report.put("costAttestation", attestation);
        report.put("maxRequests", MAX_REQUESTS);

        // 1. Create a brand new Custom connection through the same service the UI calls.
        ModelProviderRecord a = connections.create(new ModelProvidersService.Patch(
                null, "Gemini Live A", "CHAT_COMPLETIONS", BASE_URL, key, PRIMARY_MODEL, "MANUAL"));
        report.put("connectionId", a.id().toString());
        report.put("configRevision", a.configRevision());

        // 2. Discovery consumes no inference request; the catalog is not an allow-list.
        var discovery = connections.listModels(a.id());
        // Gemini's OpenAI-compatible catalog publishes "models/<id>" while the chat
        // endpoint accepts the bare id. The catalog is discovery metadata, not an
        // allow-list, so the bare id stays the selected model either way.
        boolean listed = discovery.allModels().contains(PRIMARY_MODEL)
                || discovery.allModels().contains("models/" + PRIMARY_MODEL);
        report.put("catalogModelCount", discovery.allModels().size());
        report.put("catalogIdPrefix", discovery.allModels().stream().findFirst()
                .filter(id -> id.startsWith("models/")).isPresent() ? "models/" : "none");
        assertThat(listed).as("selected model is discoverable in the provider catalog").isTrue();
        report.put("catalogPublishesPricing", !discovery.freeModels().isEmpty());
        if (!discovery.freeModels().contains(PRIMARY_MODEL)) {
            report.put("freeProof", "CATALOG_PRICING_ABSENT_OWNER_ATTESTATION");
        } else {
            report.put("freeProof", "CATALOG_ZERO_PRICING");
        }

        // 3. Real capability qualification: CHAT, STRUCTURED, NATIVE_TOOLS (4 requests).
        a = connections.validate(a.id());
        assertThat(a.validated()).as("current revision validated").isTrue();
        Map<String, String> status = capabilities.status(a);
        report.put("capabilities", status);
        assertThat(status).containsEntry("CHAT", "VERIFIED")
                .containsEntry("STRUCTURED", "VERIFIED")
                .containsEntry("NATIVE_TOOLS", "VERIFIED");

        // 4. Activate the new UUID; both Agents share this selection.
        connections.activate(a);
        assertThat(activeProviders.selection().providerId()).isEqualTo(a.id());

        // 5. Real project-Agent answer cycle through the Python brain and Java broker.
        Map<String, Object> projectAgent = runProjectAgent(a);
        report.put("projectAgent", projectAgent);

        // 6. Real Global Assistant native tool round trip through the Java broker.
        Map<String, Object> globalAssistant = runGlobalAssistant(a, liveProject);
        report.put("globalAssistant", globalAssistant);

        // 7. Changing the model id invalidates every qualification, without a request.
        ModelProviderRecord changed = connections.update(a.id(), new ModelProvidersService.Patch(
                null, null, null, null, null, REPLACEMENT_MODEL, null));
        assertThat(changed.validated()).isFalse();
        assertThat(capabilities.status(changed).values()).containsOnly("UNTESTED");
        assertThat(changed.configRevision()).isGreaterThan(a.configRevision());
        report.put("invalidationAfterModelChange", Map.of(
                "validated", false, "capabilities", capabilities.status(changed)));

        // 8. Re-verify the replacement model on the same UUID (4 requests).
        ModelProviderRecord reverified = connections.validate(changed.id());
        assertThat(reverified.validated()).isTrue();
        assertThat(capabilities.status(reverified).values()).containsOnly("VERIFIED");
        report.put("revalidatedCapabilities", capabilities.status(reverified));

        // 9. A second, never-validated connection is independent; only non-active rows delete.
        ModelProviderRecord b = connections.create(new ModelProvidersService.Patch(
                null, "Gemini Live B", "CHAT_COMPLETIONS", BASE_URL, key, PRIMARY_MODEL, "MANUAL"));
        assertThatThrownBy(() -> connections.delete(reverified.id()))
                .as("the active connection cannot be deleted")
                .isInstanceOf(IllegalArgumentException.class);
        connections.delete(b.id());
        assertThat(connections.list()).extracting(ModelProviderRecord::id)
                .contains(reverified.id()).doesNotContain(b.id());
        assertThat(activeProviders.selection().providerId()).isEqualTo(reverified.id());
        report.put("deleteIsolation", Map.of(
                "activeConnectionDeleteRefused", true,
                "nonActiveDeleted", b.id().toString(),
                "activeSelectionPreserved", true));

        int total = requestTotal();
        assertThat(total).as("hard provider request budget").isLessThanOrEqualTo(MAX_REQUESTS);
        report.put("requests", new LinkedHashMap<>(dispatched));
        report.put("requestsTotal", total);
        report.put("retries", 0);

        write(report);
    }

    // ------------------------------------------------------------- sub-flows

    private Map<String, Object> runProjectAgent(ModelProviderRecord connection) throws Exception {
        var given = new GivenSpec(
                "model-v2-live-project-agent",
                List.of(new GraphStep.CreateRootQuestionText(
                        "我们需要为门店搭建一个预约系统，请记录这个目标。", true)),
                new UserEvent.AnswerTipText("核心目标是让顾客可以自助选择时段预约，门店可以查看每日预约。"),
                new com.specagent.eval.RouteContextSpec(
                        com.specagent.eval.RouteContextSpec.Kind.ACTIVE_TIP, null),
                new com.specagent.eval.FocusContextSpec(null, null),
                List.of(), List.of(),
                new com.specagent.eval.BrainScript(List.of(),
                        new com.specagent.eval.BrainDecision("WAIT", Map.of()), List.of(), List.of()));
        var definition = new ScenarioDefinition(
                "model-v2-live-project-agent", "1", "Model Service V2 live project-Agent check",
                given,
                new com.specagent.eval.ExpectSpec(
                        java.util.Set.of("GRAPH_INTEGRITY"), List.of(), java.util.Set.of(),
                        java.util.Set.of(), Map.of(), Map.of(),
                        new com.specagent.eval.CallBudget(
                                List.of("STATE_UPDATE", "DECISION"), 1, 4, 2, 0, 5, 0)),
                List.of(VariantSpec.base("base", 20261023L)));

        var observation = scenarioRunner.runLive(definition, definition.variants().get(0), 20261023L);
        liveProject = scenarioRunner.lastProjectId();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("scenarioId", observation.scenarioId());
        record.put("executionResult", observation.executionResult());
        record.put("actualPrimaryAction", observation.actualPrimaryAction());
        record.put("productionModelCalls", observation.productionModelCalls());
        record.put("providerRetries", observation.providerRetries());
        record.put("inputTokens", observation.inputTokens());
        record.put("outputTokens", observation.outputTokens());
        record.put("latencyMs", observation.latencyMs());
        record.put("model", observation.model());
        record.put("provider", observation.provider());
        record.put("violations", observation.violations().stream()
                .map(v -> v.failureClass().name() + ":" + v.detail()).toList());

        assertThat(observation.executionResult()).isEqualTo("completed");
        assertThat(observation.providerRetries()).isZero();
        assertThat(observation.productionModelCalls()).isBetween(1, 4);
        assertThat(liveProject).isNotNull();

        Map<String, Object> persisted = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM nodes WHERE project_id=?) AS nodes,
                       (SELECT count(*) FROM answers WHERE project_id=?) AS answers,
                       (SELECT count(*) FROM answer_patches WHERE project_id=?) AS patches
                """, liveProject, liveProject, liveProject);
        record.put("persisted", persisted);
        assertThat(((Number) persisted.get("nodes")).intValue()).isPositive();
        assertThat(((Number) persisted.get("answers")).intValue()).isPositive();

        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT model_connection_reference, model_binding_fingerprint, status
                FROM agent_runs WHERE project_id=? ORDER BY created_at
                """, liveProject);
        record.put("runs", runs);
        assertThat(runs).isNotEmpty();
        assertThat(runs).allSatisfy(row -> {
            assertThat(String.valueOf(row.get("status")).toLowerCase())
                    .isIn("completed", "succeeded");
            assertThat(String.valueOf(row.get("model_connection_reference")))
                    .as("the project run froze the exact connection UUID and revision")
                    .isEqualTo("custom-provider:" + connection.id() + ":" + connection.configRevision());
            assertThat(String.valueOf(row.get("model_binding_fingerprint"))).isNotBlank();
        });
        return record;
    }

    private Map<String, Object> runGlobalAssistant(ModelProviderRecord connection, UUID project) throws Exception {
        assertThat(project).isNotNull();
        UUID thread = conversations.createThread().id();
        UUID run = null;
        Map<String, Object> record = new LinkedHashMap<>();
        try {
            var catalog = catalogs.snapshot(catalogs.current().descriptors().stream()
                    .filter(d -> d.capabilityId().equals("project.get_summary")).toList());
            assertThat(catalog.descriptors()).hasSize(1);
            run = conversations.createRun(thread, "ga.v1", "ga.v1", catalog.hash()).id();
            UUID lease = UUID.randomUUID();
            UUID binding = UUID.randomUUID();
            var ready = (GaExecutionModelTargetResolver.Ready) targets.resolveActive();
            assertThat(ready.mode()).as("native tool mode requires verified capability").isEqualTo(GaExecutionMode.NATIVE_TOOLS);
            var target = ready.target();
            record.put("mode", ready.mode().name());
            record.put("frozenSettingsRevision", target.settingsRevision());
            record.put("model", target.model());
            assertThat(target.settingsRevision()).contains(connection.id().toString());

            executions.initialize(
                    new GaExecutionStore.Scope(run, 1, lease),
                    new GaExecutionStore.Binding(thread, binding, target.provider(), target.model(),
                            target.settingsRevision(), Instant.now().plusSeconds(120), catalog.tools()),
                    catalog.descriptors());
            jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", run);

            var request = com.specagent.common.Maps.of(
                    "protocolVersion", "ga-execution.v1",
                    "engineVersion", "langchain-ga.v1",
                    "threadId", thread.toString(),
                    "runId", run.toString(),
                    "executionEpoch", 1,
                    "leaseId", lease.toString(),
                    "messageId", UUID.randomUUID().toString(),
                    "content", "请用项目摘要工具查看项目 " + project + " 的当前摘要，然后用一句话回答。",
                    "modelBindingId", binding.toString(),
                    "policyVersion", "1",
                    "catalogHash", catalog.hash(),
                    "historyBoundary", null,
                    "history", List.of(),
                    "uiContext", Map.of("currentPage", "PROJECT", "selectedEntity", project.toString()),
                    "structuredRefs", List.of(),
                    "capabilities", catalog.descriptors(),
                    "budget", Map.of("maxModelCalls", 2, "maxToolCalls", 1, "maxDurationSeconds", 120));
            Path output = evidenceDir.resolve("ga-observation.json");
            Files.deleteIfExists(output);

            Path root = Path.of("..").toAbsolutePath().normalize();
            var builder = new ProcessBuilder(com.specagent.testing.PythonExecutable.locate(root),
                    "scripts/verify_ga_r3_delivery.py");
            builder.directory(root.resolve("agent-brain").toFile());
            builder.environment().put("SPEC_AGENT_GA_TEST_HOST", "http://127.0.0.1:18086");
            builder.environment().put("SPEC_AGENT_GA_TEST_INTERNAL_TOKEN", brain.getInternalSecret());
            builder.environment().put("SPEC_AGENT_R3_GA_OUTPUT", output.toString());
            builder.redirectErrorStream(true)
                    .redirectOutput(evidenceDir.resolve("ga-python.log").toFile());
            var process = builder.start();
            try {
                process.getOutputStream().write(json.writeValueAsBytes(request));
                process.getOutputStream().close();
                assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("GA python execution timeout").isTrue();
                assertThat(process.exitValue()).as("GA python execution exit code").isZero();
            } finally {
                process.destroyForcibly();
            }

            JsonNode observed = json.readTree(Files.readString(output));
            assertThat(observed.path("hostObservationSucceeded").asBoolean()).isTrue();
            assertThat(observed.path("checkpointRestored").asBoolean()).isTrue();
            JsonNode messages = observed.path("messages");
            String finalContent = messages.get(messages.size() - 1).path("content").asText();
            assertThat(finalContent).isNotBlank();

            List<Map<String, Object>> ledger = jdbc.queryForList(
                    "SELECT model_calls,tool_calls FROM ga_executions WHERE run_id=?", run);
            List<Map<String, Object>> invocations = jdbc.queryForList(
                    "SELECT capability_id,status FROM capability_invocations WHERE run_id=?", run);
            record.put("hostObservationSucceeded", true);
            record.put("checkpointRestored", true);
            record.put("messagesCount", messages.size());
            record.put("finalAnswerLength", finalContent.length());
            record.put("gaExecutionLedger", ledger);
            record.put("capabilityInvocations", invocations);
            record.put("promptTokens", gaPromptTokens.get());
            record.put("completionTokens", gaCompletionTokens.get());
            assertThat(invocations).isNotEmpty();
            assertThat(invocations).anySatisfy(row -> assertThat(String.valueOf(row.get("status")))
                    .isIn("SUCCEEDED", "succeeded"));
        } finally {
            if (run != null) {
                jdbc.update("UPDATE global_assistant_runs SET status='COMPLETED' WHERE id=?", run);
            }
            conversationDelete.deleteThread(thread);
        }
        return record;
    }

    // --------------------------------------------------------------- plumbing

    /** One entry per provider round trip; the native verifier sends two. */
    private static int weight(String kind) {
        return "NATIVE_ROUND_TRIP".equals(kind) ? 2 : 1;
    }

    private Object dispatch(String kind, org.mockito.invocation.InvocationOnMock call) throws Throwable {
        dispatched.merge(kind, 1, Integer::sum);
        int total = dispatched.entrySet().stream()
                .mapToInt(entry -> weight(entry.getKey()) * entry.getValue()).sum();
        if (total > MAX_REQUESTS) {
            throw new IllegalStateException("Underlying provider request budget exceeded before dispatch: " + kind);
        }
        return call.callRealMethod();
    }

    private int requestTotal() {
        return dispatched.entrySet().stream()
                .mapToInt(entry -> weight(entry.getKey()) * entry.getValue()).sum();
    }

    private String readKey() throws Exception {
        String fromEnv = System.getenv("SPEC_AGENT_GEMINI_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        Path desktop = Path.of(System.getProperty("user.home"), "Desktop", "key2.txt");
        String key = Files.readString(desktop, StandardCharsets.UTF_8).strip();
        assertThat(key).isNotBlank();
        return key;
    }

    private void write(Map<String, Object> report) throws Exception {
        Path output = evidenceDir.resolve("acceptance.json");
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
    }
}
