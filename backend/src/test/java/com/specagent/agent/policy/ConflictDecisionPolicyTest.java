package com.specagent.agent.policy;

import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A model-authored DECISION changes confirmed product intent. Even when it is
 * append-only at the current route tip it must never be silently auto-applied;
 * explicit user confirmation is the deterministic backstop if the model
 * incorrectly infers delegation from natural language.
 */
class ConflictDecisionPolicyTest {

    @Test
    void decisionNodeAtCurrentTipRequiresConfirmation() {
        RouteRepository routeRepository = mock(RouteRepository.class);
        com.specagent.node.NodeRepository nodeRepository =
                mock(com.specagent.node.NodeRepository.class);
        AdvisorPolicyEngine engine = new AdvisorPolicyEngine(
                routeRepository, new CapabilityRegistry(List.of()), nodeRepository);

        UUID routeId = UUID.randomUUID();
        UUID tipNodeId = UUID.randomUUID();
        Route route = mock(Route.class);
        when(route.tipNodeId()).thenReturn(tipNodeId);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));

        ActionProposal proposal = new ActionProposal(
                "CREATE_NODE",
                Map.of(
                        "kind", "KNOWLEDGE",
                        "subtype", "DECISION",
                        "content", Map.of("text", "决定缩小首版范围以匹配当前资源。")),
                UUID.randomUUID(), "hash", List.of(), UUID.randomUUID(),
                "decision-proposal", List.of());
        ActionExecutionContext context = new ActionExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), routeId,
                UUID.randomUUID(), tipNodeId, null, null);

        PolicyDecision decision = engine.evaluate(proposal, context);

        assertThat(decision.autoExecute()).isFalse();
        assertThat(decision.requiresConfirmation()).isTrue();
        assertThat(decision.classification())
                .isEqualTo(MutationClass.CONFIRMED_INTENT_CHANGE);
    }
}
