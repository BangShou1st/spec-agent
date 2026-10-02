package com.specagent.assistant.tool;

import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.retrieval.protocol.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** GA-only readiness and bounded HELP jobs; never indexes project content or changes project engine selection. */
@Service
public class GaRetrievalReadiness {
    private final com.specagent.assistant.config.GaBrainSettings connection;
    private final SharedRetrievalHost shared;
    private final RetrievalStore store;
    private final boolean enabled;
    private volatile String status="PYTHON_UNAVAILABLE";
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public GaRetrievalReadiness(com.specagent.assistant.config.GaBrainSettings connection, GaRetrievalAccess shared, RetrievalStore store,
            @Value("${spec.global-assistant.retrieval-enabled:true}") boolean enabled, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc=jdbc; this.connection=connection; this.shared=shared.host(); this.store=store; this.enabled=enabled;
    }
    public boolean ready() { return "READY".equals(status); }
    public String status() { return status; }
    @Scheduled(fixedDelayString="${spec.global-assistant.retrieval-readiness-ms:15000}", initialDelay=3000)
    public void refresh() {
        if (!enabled) { status="DISABLED"; return; }
        if (connection.getInternalSecret().isBlank()) { status="PYTHON_UNAVAILABLE"; return; }
        try (var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request=HttpRequest.newBuilder(URI.create(connection.getBaseUrl()).resolve("/internal/v1/retrieval/health"))
                    .timeout(Duration.ofSeconds(3)).header("X-Spec-Agent-Internal-Token",connection.getInternalSecret()).GET().build();
            var future=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try { response=future.get(3,TimeUnit.SECONDS); } finally { future.cancel(true); }
            if (response.statusCode()!=200 || response.body().length()>8192) { status="PYTHON_UNAVAILABLE"; return; }
            var result=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            if (!"python-rag.v1".equals(result.path("retrievalEngineVersion").asText())) { status="PYTHON_UNAVAILABLE"; return; }
            if (!result.path("storeReady").asBoolean()) { status="STORE_UNAVAILABLE"; return; }
            if (!result.path("ollamaReady").asBoolean() || !store.activeHelpProfile().equals(result.path("profile").path("profileId").asText())) {
                status="OLLAMA_UNAVAILABLE"; return;
            }
            var generation=store.ensureHelpGeneration();
            if(RetrievalWire.PROFILE.equals(store.activeHelpProfile())) shared.splitOneHelp(generation); // legacy compatibility only
            shared.indexOneBatch(CuratedHelpSources.CORPUS,generation);
            status=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM retrieval_entries WHERE corpus_id=? AND index_generation=? AND profile_id=? AND embedding_status='READY' AND retracted_at IS NULL)",Boolean.class,CuratedHelpSources.CORPUS,generation,store.activeHelpProfile())) ? "READY" : "HELP_INDEX_NOT_READY";
        } catch (Exception unavailable) { status="RETRIEVAL_UNAVAILABLE"; }
    }
}
