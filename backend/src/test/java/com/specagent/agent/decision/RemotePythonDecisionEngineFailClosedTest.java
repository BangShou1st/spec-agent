package com.specagent.agent.decision;

import com.specagent.agent.runtime.AgentBrainProperties;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentContractException;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.RemotePythonDecisionEngine;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-closed behavior of the remote Python decision engine: unknown protocol
 * versions, wrong run ids, invented source refs, and unreachable brains all
 * produce typed failures. No retry, no fallback.
 */
class RemotePythonDecisionEngineFailClosedTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private HttpServer server;
    private RemotePythonDecisionEngine engine;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);

    @BeforeEach
    void startStubBrain() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/decisions", exchange -> {
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        AgentBrainProperties properties = new AgentBrainProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalSecret("test-secret");
        engine = new RemotePythonDecisionEngine(properties);
    }

    @AfterEach
    void stopStubBrain() {
        server.stop(0);
    }

    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(
                Files.readString(FIXTURES.resolve("agent-input-valid.json")),
                AgentRequestEnvelope.class);
    }

    @Test
    void unknownResponseProtocolVersionIsRejected() throws Exception {
        responseBody.set("""
                {"protocolVersion":"agent-decision.v9","runId":"22222222-2222-2222-2222-222222222222",
                 "stateUpdate":null,"observation":null,"actionProposal":null,
                 "usage":{"modelCalls":0,"promptHashes":[]},"diagnostics":{}}""");
        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("protocol");
    }

    @Test
    void responseWithUnknownFieldIsRejected() throws Exception {
        responseBody.set("""
                {"protocolVersion":"agent-decision.v2","runId":"22222222-2222-2222-2222-222222222222",
                 "mysteryField":true,"stateUpdate":null,"observation":null,"actionProposal":null,
                 "usage":{"modelCalls":0,"promptHashes":[]},"diagnostics":{}}""");
        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void responseWithWrongRunIdIsRejectedBeforeAnyUse() throws Exception {
        String valid = Files.readString(FIXTURES.resolve("decision-response-valid.json"));
        responseBody.set(valid.replace("22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333"));
        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void responseWithInventedSourceRefIsRejected() throws Exception {
        responseBody.set(Files.readString(
                FIXTURES.resolve("decision-response-invalid-invented-source-ref.json")));
        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void unreachableBrainProducesTypedFailureWithoutRetry() {
        AgentBrainProperties properties = new AgentBrainProperties();
        properties.setBaseUrl("http://localhost:1"); // nothing listens here
        properties.setConnectTimeoutMs(200);
        properties.setReadTimeoutSeconds(1);
        RemotePythonDecisionEngine isolated = new RemotePythonDecisionEngine(properties);
        assertThatThrownBy(() -> isolated.runDecision(request()))
                .isInstanceOf(AgentBrainUnavailableException.class);
    }
}
