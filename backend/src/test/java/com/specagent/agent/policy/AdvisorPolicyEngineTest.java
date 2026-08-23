package com.specagent.agent.policy;

import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdvisorPolicyEngineTest {

    private AdvisorPolicyEngine engine;
    private RouteRepository routeRepository;
    private UUID routeId;
    private UUID tipNodeId;

    @BeforeEach
    void setUp() {
        routeRepository = mock(RouteRepository.class);
        com.specagent.node.NodeRepository nodeRepository =
                mock(com.specagent.node.NodeRepository.class);
        when(nodeRepository.findById(org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenReturn(Optional.empty());
        CapabilityRegistry registry = new CapabilityRegistry(List.of(
                adapter("cap.read_only", SideEffectClass.NONE),
                adapter("cap.local_durable", SideEffectClass.LOCAL_DURABLE),
                adapter("cap.external", SideEffectClass.EXTERNAL_IRREVERSIBLE)));
        engine = new AdvisorPolicyEngine(routeRepository, registry, nodeRepository);
        routeId = UUID.randomUUID();
        tipNodeId = UUID.randomUUID();

        Route route = mock(Route.class);
        when(route.tipNodeId()).thenReturn(tipNodeId);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
    }

    @Test
    void waitIsAutoExecuted() {
        PolicyDecision decision = engine.evaluate(
                proposal("WAIT", Map.of()), context(tipNodeId));

        assertThat(decision.autoExecute()).isTrue();
        assertThat(decision.classification()).isEqualTo(MutationClass.READ_ONLY_INTERNAL);
    }

    @Test
    void respondToUserIsAutoExecuted() {
        PolicyDecision decision = engine.evaluate(
                proposal("RESPOND_TO_USER", Map.of("message", "hello")),
                context(tipNodeId));

        assertThat(decision.autoExecute()).isTrue();
        assertThat(decision.classification()).isEqualTo(MutationClass.READ_ONLY_INTERNAL);
    }

    @Test
    void requestUserInputAtTipIsAutoExecuted() {
        PolicyDecision decision = engine.evaluate(
                proposal("REQUEST_USER_INPUT", Map.of()),
                context(tipNodeId));

        assertThat(decision.autoExecute()).isTrue();
        assertThat(decision.classification()).isEqualTo(MutationClass.VISIBLE_GRAPH_MUTATION);
    }

    @Test
    void requestUserInputNotAtTipRequiresConfirmation() {
        UUID differentAnchor = UUID.randomUUID();
        PolicyDecision decision = engine.evaluate(
                proposal("REQUEST_USER_INPUT", Map.of()),
                context(differentAnchor));

        assertThat(decision.autoExecute()).isFalse();
        assertThat(decision.requiresConfirmation()).isTrue();
    }

    /**
     * Contract closure: a family with no executable runtime command path must
     * be denied outright — never classified as requiring confirmation, which
     * would produce a PROPOSED proposal that fails on acceptance.
     */
    @Test
    void updateNodeWithoutCommandPathIsDeniedNotConfirmed() {
        PolicyDecision decision = engine.evaluate(
                proposal("UPDATE_NODE", Map.of()),
                context(tipNodeId));

        assertThat(decision.requiresConfirmation()).isFalse();
        assertThat(decision.autoExecute()).isFalse();
        assertThat(decision.denyReason()).isNotBlank();
    }

    @Test
    void createRouteWithoutCommandPathIsDeniedNotConfirmed() {
        PolicyDecision decision = engine.evaluate(
                proposal("CREATE_ROUTE", Map.of()),
                context(tipNodeId));

        assertThat(decision.requiresConfirmation()).isFalse();
        assertThat(decision.denyReason()).isNotBlank();
    }

    @Test
    void generateArtifactWithoutRuntimeIsDeniedNotConfirmed() {
        PolicyDecision decision = engine.evaluate(
                proposal("GENERATE_ARTIFACT", Map.of("type", "spec")),
                context(tipNodeId));

        assertThat(decision.requiresConfirmation()).isFalse();
        assertThat(decision.denyReason()).isNotBlank();
    }

    @Test
    void semanticConnectRequiresConfirmationAndIsExecutableOnAcceptance() {
        PolicyDecision decision = engine.evaluate(
                proposal("CONNECT_NODE", Map.of("relationClass", "SEMANTIC")),
                context(tipNodeId));

        // SEMANTIC relations have a real execution path (the graph command
        // layer), so confirmation produces an acceptable proposal.
        assertThat(decision.requiresConfirmation()).isTrue();
        assertThat(decision.denyReason()).isNull();
    }

    @Test
    void continuationConnectIsDeniedBecauseOnlyCommandsMayCreateContinuations() {
        PolicyDecision decision = engine.evaluate(
                proposal("CONNECT_NODE", Map.of("relationClass", "CONTINUATION")),
                context(tipNodeId));

        assertThat(decision.requiresConfirmation()).isFalse();
        assertThat(decision.denyReason()).isNotBlank();
    }

    @Test
    void readOnlyCapabilityIsAutoExecuted() {
        PolicyDecision decision = engine.evaluate(
                proposal("INVOKE_CAPABILITY", Map.of("capabilityId", "cap.read_only")),
                context(tipNodeId));

        assertThat(decision.autoExecute()).isTrue();
        assertThat(decision.classification()).isEqualTo(MutationClass.READ_ONLY_INTERNAL);
    }

    @Test
    void localDurableCapabilityRequiresConfirmation() {
        PolicyDecision decision = engine.evaluate(
                proposal("INVOKE_CAPABILITY", Map.of("capabilityId", "cap.local_durable")),
                context(tipNodeId));

        assertThat(decision.autoExecute()).isFalse();
        assertThat(decision.requiresConfirmation()).isTrue();
        assertThat(decision.classification()).isEqualTo(MutationClass.CONFIRMED_INTENT_CHANGE);
    }

    @Test
    void externalCapabilityIsDenied() {
        PolicyDecision decision = engine.evaluate(
                proposal("INVOKE_CAPABILITY", Map.of("capabilityId", "cap.external")),
                context(tipNodeId));

        assertThat(decision.autoExecute()).isFalse();
        assertThat(decision.requiresConfirmation()).isFalse();
        assertThat(decision.classification()).isEqualTo(MutationClass.EXTERNAL_SIDE_EFFECT);
        assertThat(decision.denyReason()).contains("外部副作用");
    }

    @Test
    void unknownCapabilityIdIsDenied() {
        PolicyDecision decision = engine.evaluate(
                proposal("INVOKE_CAPABILITY", Map.of("capabilityId", "no.such.capability")),
                context(tipNodeId));

        assertThat(decision.autoExecute()).isFalse();
        assertThat(decision.requiresConfirmation()).isFalse();
        assertThat(decision.denyReason()).contains("未知能力");
    }

    @Test
    void blankCapabilityIdIsDenied() {
        PolicyDecision decision = engine.evaluate(
                proposal("INVOKE_CAPABILITY", Map.of()),
                context(tipNodeId));

        assertThat(decision.denyReason()).isNotNull();
    }

    @Test
    void generateArtifactIsDeniedWhileNoArtifactRuntimeExists() {
        PolicyDecision decision = engine.evaluate(
                proposal("GENERATE_ARTIFACT", Map.of("type", "spec")),
                context(tipNodeId));

        assertThat(decision.autoExecute()).isFalse();
        assertThat(decision.requiresConfirmation()).isFalse();
        assertThat(decision.denyReason()).isNotBlank();
    }

    @Test
    void confidenceDoesNotAuthorizeExecution() {
        // Even with high confidence, a family without an execution path stays
        // denied and a destructive mutation still requires confirmation —
        // confidence is never an authorization signal.
        PolicyDecision unsupported = engine.evaluate(
                proposal("UPDATE_NODE", Map.of("confidence", 0.95)),
                context(tipNodeId));

        assertThat(unsupported.autoExecute()).isFalse();
        assertThat(unsupported.requiresConfirmation()).isFalse();
        assertThat(unsupported.denyReason()).isNotBlank();
    }

    private CapabilityAdapter adapter(String id, SideEffectClass sideEffectClass) {
        return new CapabilityAdapter() {
            @Override
            public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, "1", "test capability",
                        Map.of(), Map.of(), sideEffectClass == SideEffectClass.NONE,
                        sideEffectClass, List.of(), List.of());
            }

            @Override
            public CapabilityResult invoke(CapabilityInvocation invocation) {
                return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                        id, CapabilityResult.Status.SUCCEEDED, Map.of(), List.of(), Map.of(), List.of());
            }
        };
    }

    private ActionProposal proposal(String family, Map<String, Object> payload) {
        return new ActionProposal(
                family, payload, UUID.randomUUID(), "hash",
                List.of(), UUID.randomUUID(), "idemp-1", List.of());
    }

    private ActionExecutionContext context(UUID anchorNodeId) {
        return new ActionExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), routeId,
                UUID.randomUUID(), anchorNodeId, null, null);
    }
}
