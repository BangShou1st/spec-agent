package com.specagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.provider.*;
import com.specagent.modelsettings.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Explicit read-only qualification; no Spring boot, migrations, recovery or business writes. */
public final class GaNativeQualification {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("recordedAt", Instant.now().toString());
        evidence.put("scope", "provider-native-sse-only; synthetic read-only tool result; not host RPC qualification");
        evidence.put("retries", 0);
        evidence.put("brokerIntegration", "NOT_RUN");
        evidence.put("productionAcceptance", "NOT_RUN");
        int calls = 0;
        long started = System.nanoTime();
        try {
            var db = new DriverManagerDataSource(
                    env("SPEC_AGENT_GA_QUALIFICATION_DB_URL", "jdbc:postgresql://localhost:5434/spec_agent"),
                    env("SPEC_AGENT_DB_USER", "spec_agent"), env("SPEC_AGENT_DB_PASSWORD", "spec_agent_dev"));
            var jdbc = new NamedParameterJdbcTemplate(db);
            var provider = new ModelProviderSettingsService(new JdbcModelProviderSettingsRepository(jdbc));
            if (provider.activeProvider() != com.specagent.model.contract.ModelProvider.OPENCODE_ZEN)
                throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
            Path keyFile = Path.of(env("SPEC_AGENT_GA_QUALIFICATION_KEY_FILE", "data/secret-master.key"));
            String masterKey = env("SPEC_AGENT_SECRET_MASTER_KEY", "");
            if (masterKey.isBlank() && !Files.isRegularFile(keyFile))
                throw new IllegalStateException("Existing credential key file required");
            var crypto = new ModelCredentialCrypto(masterKey, "", keyFile.toString());
            var transport = new HttpOpenCodeZenTransport(mapper, OpenCodeZenTransport.BASE_URL, 45,
                    env("SPEC_AGENT_OPENCODE_PROXY", "DIRECT"));
            var settings = new OpenCodeSettingsService(new JdbcOpenCodeSettingsRepository(jdbc, crypto),
                    new OpenCodeModelCatalog(transport), transport).requireRuntimeSettings();
            evidence.put("provider", "OPENCODE_ZEN");
            evidence.put("model", settings.selectedModel());
            var template = GaModelContract.readRequest(Files.readString(
                    Path.of("../contracts/global-assistant/fixtures/ga-model-request-valid.json")));
            UUID run = UUID.randomUUID(), lease = UUID.randomUUID(), binding = UUID.randomUUID();
            List<GaModelContract.Message> messages = new ArrayList<>(List.of(
                    new GaModelContract.Message("system", "Qualification: call project_search exactly once with query GA_NATIVE_QUALIFICATION. Then report the tool result token verbatim. Do not invent a result.", null, null),
                    new GaModelContract.Message("user", "Search the qualification project.", null, null)));
            calls++;
            var first = transport.completeNativeGa(settings.apiKey(), OpenCodeZenSessionIds.forConversation(run),
                    request(run, lease, binding, messages, template.tools(), "required"), settings.selectedModel(),
                    Duration.ofSeconds(90), () -> true);
            if (first.toolCalls().size() != 1 || !first.toolCalls().getFirst().name().equals("project_search")
                    || !first.toolCalls().getFirst().arguments().equals(Map.of("query", "GA_NATIVE_QUALIFICATION")))
                throw new IllegalArgumentException("Unexpected qualification tool call");
            evidence.put("nativeToolCall", "PASS");
            messages.add(new GaModelContract.Message("assistant", first.content(), first.toolCalls(), null));
            String token = "QUALIFICATION_RESULT_" + UUID.randomUUID();
            messages.add(new GaModelContract.Message("tool", mapper.writeValueAsString(Map.of("result", token)),
                    null, first.toolCalls().getFirst().id()));
            calls++;
            var followup = transport.completeNativeGa(settings.apiKey(), OpenCodeZenSessionIds.forConversation(run),
                    request(run, lease, binding, messages, template.tools(), "none"), settings.selectedModel(),
                    Duration.ofSeconds(90), () -> true);
            if (!followup.toolCalls().isEmpty() || !followup.content().contains(token))
                throw new IllegalArgumentException("Invalid qualification followup");
            evidence.put("toolResultFollowup", "PASS");
            evidence.put("status", "PASS");
        } catch (OpenCodeModelException ex) {
            evidence.put("status", "FAIL");
            evidence.put("failureCategory", ex.category().name());
            evidence.put("httpStatus", ex.httpStatus());
            evidence.put("freeTierGate", "FreeTierError".equals(ex.diagnostics().providerType())
                    || "FreeTierError".equals(ex.diagnostics().providerCode()));
            // No raw body, credential, private reasoning or exception message.
        } catch (RuntimeException ex) {
            evidence.put("status", "FAIL");
            evidence.put("failureCategory", ex.getClass().getSimpleName());
            if (ex instanceof IllegalArgumentException && ex.getMessage() != null
                    && ex.getMessage().matches("GA_NATIVE_STREAM_[A-Z_]+")) evidence.put("failureCode", ex.getMessage());
            if (ex instanceof GaNativeStreamException stream) evidence.put("frameShapes", stream.frames());
        }
        evidence.put("providerCalls", calls);
        evidence.put("elapsedMillis", Duration.ofNanos(System.nanoTime() - started).toMillis());
        Path output = Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_NATIVE_SSE_QUALIFICATION.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);
        Files.writeString(output.resolveSibling("GLOBAL_ASSISTANT_NATIVE_SSE_ATTEMPTS.jsonl"),
                mapper.writeValueAsString(evidence) + "\n", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        System.out.println(mapper.writeValueAsString(evidence));
    }

    private static GaModelContract.Request request(UUID run, UUID lease, UUID binding,
            List<GaModelContract.Message> messages, List<GaModelContract.Tool> tools, String choice) {
        return new GaModelContract.Request(GaModelContract.VERSION, run, 1, lease, UUID.randomUUID(),
                "AGENT", binding, messages, tools, choice, 1024, false);
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
