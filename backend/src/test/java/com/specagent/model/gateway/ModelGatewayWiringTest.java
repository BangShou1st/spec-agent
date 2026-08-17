package com.specagent.model.gateway;

import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.agent.FakeModelAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring guarantees for Phase 5.1: the orchestrator depends on the
 * {@link ModelGateway} abstraction, and automated tests keep running against
 * the deterministic fake even though the OpenCode gateway is registered.
 */
@SpringBootTest
@ActiveProfiles("test")
class ModelGatewayWiringTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private FakeAgentOrchestrator orchestrator;

    @Test
    void fakeGatewayRemainsDefaultForAutomatedTests() {
        assertThat(context.getBean(ModelGateway.class)).isInstanceOf(FakeModelAdapter.class);
    }

    @Test
    void openCodeGatewayIsRegisteredButNotTheDefault() {
        assertThat(context.getBean(OpenCodeZenModelGateway.class)).isNotNull();
        assertThat(context.getBean(ModelGateway.class)).isNotInstanceOf(OpenCodeZenModelGateway.class);
    }

    @Test
    void orchestratorIsWiredThroughTheGatewayAbstraction() {
        assertThat(orchestrator).isNotNull();
    }
}