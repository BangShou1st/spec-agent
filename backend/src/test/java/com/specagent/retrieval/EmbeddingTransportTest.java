package com.specagent.retrieval;

import com.specagent.common.BrainConnectionSettings;
import com.specagent.retrieval.config.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic local OpenAI-compatible fixture; not a real cloud provider qualification. */
class EmbeddingTransportTest {
    HttpServer server;
    String response="{\"data\":[{\"index\":1,\"embedding\":[0,2,0]},{\"index\":0,\"embedding\":[1,0,0]}]}";
    int status=200, calls;
    String body;
    String authorization;
    EmbeddingTransport transport;
    EmbeddingSettings.Config config;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",exchange->{
            calls++; authorization=exchange.getRequestHeaders().getFirst("Authorization");
            body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        config=new EmbeddingSettings.Config("OPENAI_COMPATIBLE","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-model",3,8,"raw-text.v1");
        transport=new EmbeddingTransport(new BrainConnectionSettings() {
            public String getBaseUrl() { return "http://127.0.0.1"; }
            public String getInternalSecret() { return "fixture-internal"; }
        },"http://127.0.0.1");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void officialEmbeddingsPathFloatFormatAndIndexOrderingAreEnforced() {
        var vectors=transport.remote(config,"fixture-key",List.of("document","query"));
        assertArrayEquals(new double[]{1,0,0},vectors.getFirst()); assertArrayEquals(new double[]{0,2,0},vectors.getLast());
        assertTrue(body.contains("\"encoding_format\":\"float\"")); assertFalse(body.contains("dimensions"));
        assertFalse(body.contains("fixture-key")); assertEquals("Bearer fixture-key",authorization); assertEquals(1,calls);
    }
    @Test void malformedArraysAndDimensionDriftNeverPassAsAValidProbe() {
        for(String invalid:List.of(
                "{\"choices\":[{\"message\":{\"content\":\"chat worked\"}}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[1,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":1,\"embedding\":[1,0,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[0,0]},{\"index\":1,\"embedding\":[1,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":\"base64\"}]}")) {
            response=invalid; assertThrows(IllegalStateException.class,()->transport.probe(config,"fixture-key"));
        }
    }
    @Test void providerFailuresAreTypedAndNeverIncludeItsCredentialBearingBody() {
        response="{\"error\":\"fixture-key and private provider body\"}";
        for(int code:List.of(401,403,429,500,302)) {
            status=code;
            var error=assertThrows(IllegalStateException.class,()->transport.remote(config,"fixture-key",List.of("document","query")));
            assertFalse(error.toString().contains("fixture-key"));
            assertEquals(code==401||code==403?"EMBEDDING_AUTHENTICATION_FAILED":code==429?"EMBEDDING_RATE_LIMITED":"EMBEDDING_PROVIDER_FAILED",error.getMessage());
        }
    }
}
