package com.specagent.agent.decision;

import com.specagent.agent.protocol.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Host transport boundary must enforce grounding even in V2/shadow mode. */
class AgentBrainArgumentGroundingTest {
    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(Files.readString(Path.of("../contracts/fixtures/agent-input-valid.json")),
                AgentRequestEnvelope.class);
    }

    private AgentResponseEnvelope response(AgentRequestEnvelope request, Map<String, Object> arguments) {
        return new AgentResponseEnvelope("agent-decision.v2", request.runId(), null,
                new ObservationView(List.of(), List.of(), List.of(), List.of()),
                new ActionProposal("INVOKE_CAPABILITY", Map.of("capabilityId", "probe.read", "arguments", arguments),
                        UUID.fromString(request.snapshot().snapshotId()), request.snapshot().contextHash(),
                        List.of(), UUID.randomUUID(), request.runId().toString(), List.of()), null, Map.of());
    }

    @Test void nestedForeignRefsFailAtTheHostBoundaryWithoutEchoingTheValue() throws Exception {
        var request = request();
        for (String prefix : List.of("node:", "answer:", "patch:", "context:", "route:")) {
            String foreign = prefix + "11111111-2222-3333-4444-555555555555";
            assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request,
                    response(request, Map.of("filters", List.of(Map.of("source", List.of(foreign)))))))
                    .isInstanceOf(AgentContractException.class)
                    .hasMessageContaining("argument ref").hasMessageNotContaining(foreign)
                    .satisfies(failure -> {
                        var diagnostic = (AgentContractException) failure;
                        assertThat(diagnostic.diagnosticCode()).isEqualTo("JAVA_RESPONSE_CONTRACT_INVALID");
                        assertThat(diagnostic.fieldPath()).isEqualTo("actionProposal.payload.arguments");
                    });
        }
    }

    @Test void allowedNestedRefsAndOrdinaryValuesRemainLegal() throws Exception {
        var request = request();
        var nested = new ArrayList<Object>();
        nested.add(Map.of("source", request.snapshot().allowedSourceRefs().getFirst()));
        nested.addAll(Arrays.asList(null, false, 0, "ordinary query"));
        assertThatCode(() -> AgentBrainResponseValidator.validateDecision(request,
                response(request, Map.of("filters", nested)))).doesNotThrowAnyException();
    }
}
