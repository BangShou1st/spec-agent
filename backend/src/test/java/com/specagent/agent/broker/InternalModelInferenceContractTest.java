package com.specagent.agent.broker;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.AgentBrainProperties;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.model.inference.ModelOutputContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for BUG-03: the internal inference broker serves the Python
 * agent brain, which parses model output as strict JSON
 * ({@code engine._parse_model_output -> json.loads}) and fails closed with a
 * 502 {@code brain_failure} on anything else (markdown-fenced or prose-wrapped
 * JSON). The broker therefore MUST request a structured JSON output contract
 * for the brain's calls so the model cannot return non-JSON text.
 *
 * <p>Before the fix the broker built the request with the historical
 * {@code text()} contract (no {@code response_format}), letting a real model
 * emit fenced JSON and break every draft-question run with INTERNAL_ERROR.
 * This test asserts the broker requests {@link ModelOutputContract.JsonObject}
 * for every closed brain call type — STATE_UPDATE, DECISION, ARTIFACT_GENERATION
 * — through a single table-driven case. It FAILS on the old behavior and PASSES
 * after the fix.
 */
@ExtendWith(MockitoExtension.class)
class InternalModelInferenceContractTest {

    @Mock
    private ModelInferenceGateway gateway;
    @Mock
    private AgentRunEventService eventService;
    @Mock
    private AgentBrainProperties properties;
    @Mock
    private RunExistenceCheck runExistenceCheck;

    @BeforeEach
    void setUp() {
        when(properties.getInternalSecret()).thenReturn("dev-internal-secret");
        when(properties.getBroker()).thenReturn(new AgentBrainProperties.Broker());
        when(runExistenceCheck.exists(any())).thenReturn(true);
        when(gateway.complete(any(ModelInferenceRequest.class)))
                .thenReturn(new ModelInferenceResponse("{}", "stop", 0, 0));
    }

    private InternalModelInferenceController controller() {
        return new InternalModelInferenceController(gateway, eventService, properties, runExistenceCheck);
    }

    private String bodyFor(String callType) {
        ModelInferenceHttpRequest request = new ModelInferenceHttpRequest(
                AgentProtocol.INFERENCE_PROTOCOL_VERSION,
                UUID.randomUUID(),
                callType,
                List.of(new ModelInferenceHttpRequest.Message("user", "hi")),
                1000);
        return AgentContracts.write(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATE_UPDATE", "DECISION", "ARTIFACT_GENERATION"})
    void brainCallRequestsJsonObjectContract(String callType) {
        controller().complete("dev-internal-secret", bodyFor(callType));

        ArgumentCaptor<ModelInferenceRequest> captor = ArgumentCaptor.forClass(ModelInferenceRequest.class);
        verify(gateway).complete(captor.capture());
        assertThat(captor.getValue().outputContract())
                .describedAs("Brain call type %s must request structured JSON so the model "
                        + "cannot return fenced/prose JSON that breaks json.loads", callType)
                .isInstanceOf(ModelOutputContract.JsonObject.class);
    }
}
