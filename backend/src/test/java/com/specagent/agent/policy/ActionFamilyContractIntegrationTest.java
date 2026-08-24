package com.specagent.agent.policy;

import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import com.specagent.graph.GraphCommandService;
import com.specagent.graph.GraphOperation;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage support-matrix contract (hardening): every action family the
 * response validator accepts receives a deterministic policy verdict, and
 * every verdict that persists a PROPOSED proposal corresponds to an
 * acceptance that executes successfully. A proposal the user can accept but
 * whose acceptance is guaranteed to fail must not be able to exist.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ActionFamilyContractIntegrationTest {

    @TestConfiguration
    static class LocalDurableCapabilityConfig {

        @Bean
        CapabilityAdapter localDurableTestCapability() {
            return new CapabilityAdapter() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor("test.local_durable", "1",
                            "local durable test capability", Map.of(), Map.of(),
                            false, SideEffectClass.LOCAL_DURABLE, List.of(), List.of());
                }

                @Override
                public CapabilityResult invoke(CapabilityInvocation invocation) {
                    return new CapabilityResult(invocation.invocationId(),
                            invocation.invocationKey(), invocation.capabilityId(),
                            CapabilityResult.Status.SUCCEEDED,
                            Map.of("ok", true), List.of(), Map.of(), List.of());
                }
            };
        }
    }

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private AdvisorPolicyEngine policyEngine;
    @Autowired private AgentProposalService proposalService;
    @Autowired private ProposalAcceptanceService acceptanceService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private com.specagent.node.NodeService nodeService;

    private Project project;
    private Route route;
    private Node root;
    private Node tip;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("行动族契约测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        tip = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "REQUIREMENT",
                Map.of("text", "tip")).node();
    }

    private PolicyDecision verdict(String family, Map<String, Object> payload,
                                   UUID anchorNodeId) {
        ActionProposal proposal = new ActionProposal(family, payload,
                UUID.randomUUID(), "hash", List.of(), UUID.randomUUID(),
                "idem-" + UUID.randomUUID(), List.of());
        return policyEngine.evaluate(proposal, context(anchorNodeId));
    }

    private ActionExecutionContext context(UUID anchorNodeId) {
        return new ActionExecutionContext(UUID.randomUUID(), project.id(), route.id(),
                UUID.randomUUID(), anchorNodeId, null, null);
    }

    @Test
    void everyValidatorAcceptedFamilyHasADeterministicVerdict() {
        // Keep this loop closed over the Java enum so adding a validator family
        // cannot silently bypass the runtime policy matrix.
        for (ActionFamily family : ActionFamily.values()) {
            PolicyDecision decision = verdict(family.code(), payloadFor(family), tip.id());
            switch (family) {
                case WAIT, RESPOND_TO_USER, REQUEST_USER_INPUT, CREATE_NODE ->
                        assertThat(decision.autoExecute()).as(family.code()).isTrue();
                case CONNECT_NODE, INVOKE_CAPABILITY ->
                        assertThat(decision.requiresConfirmation()).as(family.code()).isTrue();
                case UPDATE_NODE, CREATE_ROUTE, GENERATE_ARTIFACT -> {
                    assertThat(decision.autoExecute()).as(family.code()).isFalse();
                    assertThat(decision.requiresConfirmation()).as(family.code()).isFalse();
                    assertThat(decision.denyReason()).as(family.code()).isNotBlank();
                }
            }
        }

        // CONTINUATION topology belongs to the continuation commands.
        assertThat(verdict("CONNECT_NODE",
                Map.of("relationClass", "CONTINUATION"), tip.id()).denyReason()).isNotBlank();

        // Unknown / external capabilities stay denied.
        assertThat(verdict("INVOKE_CAPABILITY",
                Map.of("capabilityId", "no.such.capability"), tip.id()).denyReason()).isNotBlank();
    }

    @Test
    void localDurableCapabilityProposalExecutesOnAcceptanceThroughRuntime() {
        AgentProposal pending = persistPending("INVOKE_CAPABILITY",
                Map.of("capabilityId", "test.local_durable",
                       "arguments", Map.of("note", "bounded local work")));

        ProposalAcceptanceService.AcceptedProposalResult result =
                acceptanceService.acceptAndExecute(pending.id(), "user");

        assertThat(result.actionFamily()).isEqualTo("INVOKE_CAPABILITY");
        assertThat(proposalService.getProposal(pending.id()).orElseThrow().status())
                .isEqualTo(ProposalStatus.ACCEPTED);
        assertThat(commandService.listOperations(project.id())).anySatisfy(op -> {
            assertThat(op.type()).isEqualTo(GraphOperation.Type.ACCEPT_AGENT_PROPOSAL);
            assertThat(op.causedBy()).isEqualTo("proposal:" + pending.id());
        });
    }

    @Test
    void staleCapabilityEndpointIsRejectedAtAcceptanceAndStaysPending() {
        Node resource = commandService.attachResource(
                project.id(), route.id(), tip.id(), "TEXT", Map.of("text", "source"));
        AgentProposal pending = persistPending("INVOKE_CAPABILITY",
                Map.of("capabilityId", "test.local_durable",
                       "arguments", Map.of("nodeRef", "node:" + resource.id())));

        // The endpoint node is retracted before acceptance; acceptance must
        // reject instead of executing against dead graph state.
        nodeService.setRetracted(resource.id(), true);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> acceptanceService.acceptAndExecute(pending.id(), "user"))
                .isInstanceOf(com.specagent.agent.action.StaleProposalException.class);
        assertThat(proposalService.getProposal(pending.id()).orElseThrow().status())
                .isEqualTo(ProposalStatus.PROPOSED);
    }

    @Test
    void nonTipAnchorMutationsNeverBecomeAcceptableProposals() {
        // A historical anchor confirms under policy (not append-only), but
        // acceptance requires the anchor to still be the route tip — so the
        // proposal-creating call sites must not persist one.
        ActionProposal createAtHistory = new ActionProposal("CREATE_NODE", knowledgePayload(),
                UUID.randomUUID(), "hash", List.of(), UUID.randomUUID(),
                "idem-" + UUID.randomUUID(), List.of());
        ActionExecutionContext historicalContext = context(root.id());

        PolicyDecision decision = policyEngine.evaluate(createAtHistory, historicalContext);
        assertThat(decision.requiresConfirmation()).isTrue();
        assertThat(policyEngine.canProduceAcceptableProposal(createAtHistory, historicalContext))
                .isFalse();

        // At the live tip the same family is acceptable after confirmation.
        assertThat(policyEngine.canProduceAcceptableProposal(
                new ActionProposal("CREATE_NODE", knowledgePayload(), UUID.randomUUID(),
                        "hash", List.of(), UUID.randomUUID(), "idem-x", List.of()),
                context(tip.id()))).isTrue();
    }

    @Test
    void connectNodeWithDeadEndpointNeverBecomesAcceptable() {
        Node retractedCandidate = commandService.attachResource(
                project.id(), route.id(), tip.id(), "TEXT", Map.of("text", "doomed"));
        nodeService.setRetracted(retractedCandidate.id(), true);

        ActionProposal dead = new ActionProposal("CONNECT_NODE",
                Map.of("relationClass", "SEMANTIC", "relationType", "RELATED_TO",
                        "sourceRef", "node:" + retractedCandidate.id(),
                        "targetRef", "node:" + tip.id()),
                UUID.randomUUID(), "hash", List.of(), UUID.randomUUID(),
                "idem-" + UUID.randomUUID(), List.of());

        assertThat(policyEngine.canProduceAcceptableProposal(dead, context(tip.id())))
                .isFalse();
    }

    private AgentProposal persistPending(String family, Map<String, Object> payload) {
        ActionProposal proposal = new ActionProposal(family, payload,
                UUID.randomUUID(), "hash", List.of(), UUID.randomUUID(),
                "idem-" + UUID.randomUUID(), List.of());
        return proposalService.createProposal(proposal, UUID.randomUUID(),
                project.id(), route.id());
    }

    private Map<String, Object> interactionPayload() {
        return Map.of("questionText", "下一步关注什么？",
                "options", List.of(Map.of("label", "范围")),
                "allowFreeAnswer", true);
    }

    private Map<String, Object> knowledgePayload() {
        return Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                "content", Map.of("text", "风险内容"));
    }

    private Map<String, Object> semanticPayload() {
        return Map.of("relationClass", "SEMANTIC", "relationType", "RELATED_TO",
                "sourceRef", "node:" + tip.id(), "targetRef", "node:" + root.id());
    }

    private Map<String, Object> payloadFor(ActionFamily family) {
        return switch (family) {
            case WAIT -> Map.of();
            case RESPOND_TO_USER -> Map.of("message", "hi");
            case REQUEST_USER_INPUT -> interactionPayload();
            case CREATE_NODE -> knowledgePayload();
            case CONNECT_NODE -> semanticPayload();
            case INVOKE_CAPABILITY -> Map.of("capabilityId", "test.local_durable");
            case UPDATE_NODE, CREATE_ROUTE, GENERATE_ARTIFACT -> Map.of();
        };
    }
}
