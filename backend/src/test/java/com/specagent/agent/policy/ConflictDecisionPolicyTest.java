package com.specagent.agent.policy;

import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件名:ConflictDecisionPolicyTest.java
 *
 * 测试目标:验证模型产生的 DECISION(决策)节点策略——DECISION 变更的是已确认的产品
 * 意图,即使它 append-only 地落在当前路线 tip 上,也绝不能被静默自动应用;显式的用户确认
 * 是模型从自然语言错误推断"已获授权"时的确定性兜底。
 */
class ConflictDecisionPolicyTest {

    @Test
    void decisionNodeAtCurrentTipRequiresConfirmation() {
        RouteRepository routeRepository = mock(RouteRepository.class);
        com.specagent.workspace.node.NodeRepository nodeRepository =
                mock(com.specagent.workspace.node.NodeRepository.class);
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
