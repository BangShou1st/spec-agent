package com.specagent.agent.contract;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentContractException;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-fixture contract tests shared with the Python brain. The fixtures
 * under {@code contracts/fixtures} are the single cross-language authority:
 * valid ones must parse, invalid ones must be rejected fail-closed.
 */
class AgentV2ContractTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private String fixture(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name));
    }

    @Test
    void validRequestFixtureParses() throws Exception {
        AgentRequestEnvelope envelope =
                AgentContracts.read(fixture("agent-input-valid.json"), AgentRequestEnvelope.class);
        assertThat(envelope.runId().toString())
                .isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(envelope.snapshot().metadata().projectTitle())
                .isEqualTo("内部工单系统探索");
        assertThat(envelope.snapshot().lineage()).hasSize(2);
        // Generic Graph language only: no question-workflow names on the wire.
        String wire = fixture("agent-input-valid.json");
        assertThat(wire).doesNotContain("\"question\"");
        assertThat(wire).doesNotContain("DRAFT_NODE");
    }

    @Test
    void unknownRequestFieldIsRejected() throws Exception {
        assertThatThrownBy(() -> AgentContracts.read(
                fixture("agent-input-invalid-unknown-field.json"), AgentRequestEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void unknownRequestProtocolVersionIsRejected() throws Exception {
        assertThatThrownBy(() -> AgentContracts.read(
                fixture("agent-input-invalid-unknown-version.json"), AgentRequestEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void validDecisionResponseFixtureParses() throws Exception {
        AgentResponseEnvelope response =
                AgentContracts.read(fixture("decision-response-valid.json"),
                        AgentResponseEnvelope.class);
        assertThat(response.actionProposal().actionFamily()).isEqualTo("REQUEST_USER_INPUT");
        assertThat(response.observation().known()).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "decision-response-invalid-unknown-action-family.json",
            "decision-response-invalid-invented-source-ref.json",
            "decision-response-invalid-stale-base-context.json"
    })
    void decisionResponseFixturesRoundTripThroughStrictMapper(String name) throws Exception {
        // Schema-level: these parse (semantic rejection is the validator's job).
        AgentResponseEnvelope response =
                AgentContracts.read(fixture(name), AgentResponseEnvelope.class);
        assertThat(response.actionProposal()).isNotNull();
    }

    @Test
    void runtimeOwnedClaimIdInStateUpdateIsRejectedAtSchemaLevel() throws Exception {
        assertThatThrownBy(() -> AgentContracts.read(
                fixture("state-update-response-invalid-runtime-owned-id.json"),
                AgentResponseEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void validStateUpdateFixtureParses() throws Exception {
        AgentResponseEnvelope response =
                AgentContracts.read(fixture("state-update-response-valid.json"),
                        AgentResponseEnvelope.class);
        assertThat(response.stateUpdate().claims()).hasSize(1);
        assertThat(response.actionProposal()).isNull();
    }

    @Test
    void routelessNodeQueryFixtureParsesWithNullRouteIds() throws Exception {
        // Stage C NODE_QUERY routeless nullability: a Floating-node NODE_QUERY
        // is the only semantic flow that may carry null route ids. The
        // contract is the single cross-language authority: the same fixture
        // is parsed by both the Java strict mapper and the Python Pydantic
        // envelope.
        AgentRequestEnvelope envelope = AgentContracts.read(
                fixture("agent-input-routeless-node-query-valid.json"),
                AgentRequestEnvelope.class);
        assertThat(envelope.snapshot().routeId()).isNull();
        assertThat(envelope.snapshot().routeContext().routeId()).isNull();
        assertThat(envelope.event().kind()).isEqualTo("NODE_QUERY");
        assertThat(envelope.snapshot().anchorNodeId())
                .isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        // The route-bound baseline must keep its route ids.
        AgentRequestEnvelope baseline = AgentContracts.read(
                fixture("agent-input-valid.json"), AgentRequestEnvelope.class);
        assertThat(baseline.snapshot().routeId()).isNotNull();
        assertThat(baseline.snapshot().routeContext().routeId()).isNotNull();
    }
}
