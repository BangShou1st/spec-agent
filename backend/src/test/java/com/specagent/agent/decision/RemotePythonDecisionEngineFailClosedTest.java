package com.specagent.agent.decision;

import com.specagent.agent.runtime.AgentBrainProperties;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentContractException;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.RemotePythonDecisionEngine;
import com.specagent.agent.eligibility.ActionIneligibleException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Fail-closed behavior of the remote Python decision engine: unknown protocol
 * versions, wrong run ids, invented source refs, and unreachable brains all
 * produce typed failures. No retry, no fallback. The failure *code* keeps the
 * causes apart: a malformed model output and an out-of-range citation are not
 * "the brain is down", and a slow brain is not an unreachable one.
 */
class RemotePythonDecisionEngineFailClosedTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private HttpServer server;
    private RemotePythonDecisionEngine engine;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<Long> latencyMillis = new AtomicReference<>(0L);
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startStubBrain() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/decisions", exchange -> {
            requests.incrementAndGet();
            sleepIfSlow();
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

    private void sleepIfSlow() {
        long latency = latencyMillis.get();
        if (latency <= 0) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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

    private BrainFailureCode failureCodeOf(String body, int httpStatus) throws Exception {
        status.set(httpStatus);
        responseBody.set(body);
        Throwable failure = catchThrowable(() -> engine.runDecision(request()));
        assertThat(failure).isInstanceOf(AgentBrainUnavailableException.class);
        return ((AgentBrainUnavailableException) failure).failureCode();
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
        Throwable failure = catchThrowable(() -> isolated.runDecision(request()));

        assertThat(failure).isInstanceOf(AgentBrainUnavailableException.class);
        assertThat(((AgentBrainUnavailableException) failure).failureCode())
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    @Test
    void providerUnavailableResponseBecomesExplicitRemoteFailure() throws Exception {
        // An untyped 5xx keeps the historical opaque code: the engine only
        // classifies a cause the brain actually named.
        assertThat(failureCodeOf("{\"error\":\"provider unavailable\"}", 502))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    @Test
    void modelOutputContractViolationIsClassifiedAndNeverRetried() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:BrainContractError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
        // A deterministic contract violation is never retried by the engine.
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void ambiguousSourceRefsIsAlsoAContractViolation() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:AmbiguousSourceRefsError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
    }

    @Test
    void ungroundedReferenceIsClassifiedSeparatelyFromAMalformedOutput() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:UngroundedReferenceError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_UNGROUNDED_REFERENCE);
    }

    @Test
    void artifactUngroundedReferenceIsClassifiedToo() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:UngroundedReferenceError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_UNGROUNDED_REFERENCE);
    }

    @Test
    void providerCallFailureInsideTheBrainIsItsOwnCode() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:ModelClientError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_PROVIDER_FAILURE);
    }

    @Test
    void slowBrainIsReportedAsATimeoutNotAsAnUnreachableBrain() throws Exception {
        AgentBrainProperties properties = new AgentBrainProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalSecret("test-secret");
        properties.setConnectTimeoutMs(500);
        properties.setReadTimeoutSeconds(1);
        RemotePythonDecisionEngine impatient = new RemotePythonDecisionEngine(properties);
        latencyMillis.set(5_000L);
        responseBody.set(Files.readString(FIXTURES.resolve("decision-response-valid.json")));

        Throwable failure = catchThrowable(() -> impatient.runDecision(request()));

        assertThat(failure).isInstanceOf(AgentBrainUnavailableException.class);
        assertThat(((AgentBrainUnavailableException) failure).failureCode())
                .isEqualTo(BrainFailureCode.BRAIN_TIMEOUT);
    }

    @Test
    void v3IneligibleSelectionResponseBecomesTypedActionFailure() throws Exception {
        status.set(409);
        responseBody.set("{\"detail\":\"ACTION_INELIGIBLE\"}");

        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(ActionIneligibleException.class)
                .hasMessageContaining("ACTION_INELIGIBLE");
    }

    @Test
    void brainFailureDetailClassificationIsExplicit() {
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:BrainContractError\"}"))
                .isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:ArtifactBrainContractError\"}"))
                .isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:UngroundedReferenceError\"}"))
                .isEqualTo(BrainFailureCode.MODEL_UNGROUNDED_REFERENCE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:ModelClientError\"}"))
                .isEqualTo(BrainFailureCode.MODEL_PROVIDER_FAILURE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:BrokerTimeoutError\"}"))
                .isEqualTo(BrainFailureCode.BRAIN_TIMEOUT);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:ActionIneligibleBrainError\"}"))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(null))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail("{} "))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    @Test
    void brokerTimeoutCallIsReportedAsATimeoutNotAsProviderFailure() throws Exception {
        // A broker timeout from the brain must map to BRAIN_TIMEOUT, not
        // MODEL_PROVIDER_FAILURE, so the user sees the right diagnosis.
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:BrokerTimeoutError\"}", 502))
                .isEqualTo(BrainFailureCode.BRAIN_TIMEOUT);
    }
}
