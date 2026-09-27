package com.specagent.agent.policy;

import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
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

/**
 * 文件名:AdvisorPolicyEngineTest.java
 *
 * 测试目标:验证 AdvisorPolicyEngine 对各动作族的判定矩阵——WAIT/RESPOND_TO_USER/
 * tip 上的 REQUEST_USER_INPUT/只读能力自动执行;非 tip 锚点与 LOCAL_DURABLE 能力需确认;
 * 无执行路径的动作族(UPDATE_NODE/CREATE_ROUTE/GENERATE_ARTIFACT)直接拒绝而非"需确认";
 * CONTINUATION 连接拒绝;外部副作用能力拒绝;置信度绝不构成授权信号。
 */
class AdvisorPolicyEngineTest {

    private AdvisorPolicyEngine engine;
    private RouteRepository routeRepository;
    private UUID routeId;
    private UUID tipNodeId;

    @BeforeEach
    void setUp() {
        routeRepository = mock(RouteRepository.class);
        com.specagent.workspace.node.NodeRepository nodeRepository =
                mock(com.specagent.workspace.node.NodeRepository.class);
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
     * 契约闭环:没有可执行运行时命令路径的动作族必须被直接拒绝——绝不能归为
     * "需确认",否则会产生一个在接受时必然失败的 PROPOSED 提案。
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

        // SEMANTIC 关系有真实的执行路径(图命令层),所以确认会产生可接受的提案。
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
        // 即使置信度很高,没有执行路径的动作族仍然被拒绝,破坏性变更仍然需要
        // 确认——置信度绝不是授权信号。
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
