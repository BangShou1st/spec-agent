package com.specagent.assistant.tool;

import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.common.BrainConnectionSettings;
import com.specagent.retrieval.protocol.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GaRetrievalReadinessTest {
    HttpServer server;
    String health;
    GaRetrievalReadiness readiness;
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    SharedRetrievalHost host=mock(SharedRetrievalHost.class);
    UUID generation=UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/retrieval/health", exchange -> {
            byte[] body=health.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        var auth=mock(BrainConnectionSettings.class); when(auth.getInternalSecret()).thenReturn("fixture-token");
        var connection=new GaBrainSettings("http://127.0.0.1:"+server.getAddress().getPort(),auth);
        var access=mock(GaRetrievalAccess.class); when(access.host()).thenReturn(host);
        var store=mock(RetrievalStore.class); when(store.ensureHelpGeneration()).thenReturn(generation);
        readiness=new GaRetrievalReadiness(connection,access,store,true,jdbc);
    }
    @AfterEach void stop() { server.stop(0); }
    String health(boolean store,boolean ollama) {
        return "{\"retrievalEngineVersion\":\"python-rag.v1\",\"storeReady\":"+store+",\"ollamaReady\":"+ollama+
            ",\"profile\":{\"profileId\":\""+RetrievalWire.PROFILE+"\"}}";
    }
    @Test void unavailableDependenciesExplainWhyAndNeverPrepareIndexes() {
        health=health(false,true); readiness.refresh();
        assertFalse(readiness.ready()); assertEquals("STORE_UNAVAILABLE",readiness.status());
        health=health(true,false); readiness.refresh();
        assertFalse(readiness.ready()); assertEquals("OLLAMA_UNAVAILABLE",readiness.status());
        verifyNoInteractions(host,jdbc);
    }
    @Test void healthAloneDoesNotClaimIndexReadinessAndRecoveringIndexBecomesReady() {
        health=health(true,true); readiness.refresh();
        assertEquals("HELP_INDEX_NOT_READY",readiness.status()); assertFalse(readiness.ready());
        doReturn(true).when(jdbc).queryForObject(anyString(),eq(Boolean.class),eq(CuratedHelpSources.CORPUS),eq(generation),eq(RetrievalWire.PROFILE));
        readiness.refresh(); assertTrue(readiness.ready()); assertEquals("READY",readiness.status());
        verify(host,times(2)).indexOneBatch(CuratedHelpSources.CORPUS,generation);
    }
}
