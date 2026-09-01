package com.specagent.agent.action;

import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Concrete executor that dispatches validated action proposals by family.
 *
 * <p>Families that produce graph mutations (REQUEST_USER_INPUT, CREATE_NODE)
 * delegate to {@link AgentGraphMutationService}, the narrow transactional
 * graph-action boundary: the node insert and route tip advancement commit
 * together under the project-row lock, and the expected anchor is re-verified
 * against the CURRENT route tip inside that transaction. Auto-execute enters
 * it after the model and policy have completed; accepted proposals join the
 * same boundary through the acceptance transaction. Read-only actuator
 * families and INVOKE_CAPABILITY stay outside the graph lock.
 *
 * <p>Every execution is preceded by a stale-context liveness check. The
 * executor never invents IDs; all identity assignment is delegated to
 * runtime services.
 */
@Component
public class ProposalActionExecutor implements ActionExecutor {

    private final CapabilityRuntime capabilityRuntime;
    private final AgentGraphMutationService agentGraphMutationService;

    public ProposalActionExecutor(CapabilityRuntime capabilityRuntime,
                                  AgentGraphMutationService agentGraphMutationService) {
        this.capabilityRuntime = capabilityRuntime;
        this.agentGraphMutationService = agentGraphMutationService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ActionResult execute(ActionProposal proposal, ActionExecutionContext context) {
        ActionFamily family = ActionFamily.fromCode(proposal.actionFamily());
        return switch (family) {
            case REQUEST_USER_INPUT -> executeRequestUserInput(proposal, context);
            case CREATE_NODE -> executeCreateNode(proposal, context);
            case RESPOND_TO_USER -> executeRespondToUser(proposal);
            case WAIT -> executeWait();
            case INVOKE_CAPABILITY -> executeInvokeCapability(proposal, context);
            case UPDATE_NODE, CONNECT_NODE, CREATE_ROUTE ->
                    executeDeferredMutation(proposal);
            case GENERATE_ARTIFACT ->
                    executeUnsupported(proposal);
        };
    }

    /**
     * Invokes a capability through the runtime with a runtime-owned
     * idempotency key: retrying the same proposal replays the recorded
     * outcome (or surfaces a typed IN_PROGRESS state while an invocation is
     * still unfinished) instead of re-executing the adapter. Typed failures
     * come back as a message so the run can surface them instead of
     * crashing.
     */
    @SuppressWarnings("unchecked")
    private ActionResult executeInvokeCapability(ActionProposal proposal,
                                                 ActionExecutionContext context) {
        Map<String, Object> payload = proposal.payload();
        String capabilityId = asString(payload.get("capabilityId"), "capabilityId");
        Map<String, Object> arguments = payload.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();

        String invocationKey = "run:" + context.runId() + ":proposal:" + proposal.idempotencyKey();
        CapabilityResult result = capabilityRuntime.invoke(
                invocationKey, capabilityId, context.projectId(), context.runId(), arguments);

        String message = "capability " + capabilityId + " -> " + result.status()
                + (result.status() == CapabilityResult.Status.FAILED
                        ? ": " + result.content().get("reason") : "");
        return new ActionResult("INVOKE_CAPABILITY", null, null, message);
    }

    @SuppressWarnings("unchecked")
    private ActionResult executeRequestUserInput(ActionProposal proposal,
                                                 ActionExecutionContext context) {
        Map<String, Object> payload = proposal.payload();
        String questionText = asString(payload.get("questionText"), "questionText");
        String purpose = asString(payload.get("purpose"), "purpose");
        boolean allowFreeAnswer = payload.get("allowFreeAnswer") instanceof Boolean b && b;
        List<NodeOption> options = parseOptions(payload.get("options"));

        // The anchor is the route tip the model decided against (null on an
        // empty route). The transactional boundary re-verifies it against the
        // CURRENT tip before inserting; an anchor-less decision over a route
        // that has since gained a tip fails closed instead of mis-anchoring.
        Node node = agentGraphMutationService.executeNodeCreation(
                context.projectId(), context.routeId(), context.anchorNodeId(),
                new AgentGraphMutationService.InteractionNode(
                        questionText, purpose, options, allowFreeAnswer));

        return new ActionResult("REQUEST_USER_INPUT", node.id(), null, null);
    }

    @SuppressWarnings("unchecked")
    private ActionResult executeCreateNode(ActionProposal proposal,
                                           ActionExecutionContext context) {
        Map<String, Object> payload = proposal.payload();
        String kind = payload.get("kind") instanceof String s ? s : "INTERACTION";

        if (!"INTERACTION".equals(kind)) {
            // Generic workspace unit: payload lives in content; the runtime
            // validates the subtype whitelist at creation.
            String subtype = asString(payload.get("subtype"), "subtype");
            Map<String, Object> content = payload.get("content") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            Node node = agentGraphMutationService.executeNodeCreation(
                    context.projectId(), context.routeId(), context.anchorNodeId(),
                    new AgentGraphMutationService.WorkspaceNode(
                            com.specagent.node.NodeKind.fromCode(kind),
                            subtype, content));
            return new ActionResult("CREATE_NODE", node.id(), null, null);
        }

        // Interaction node: the question payload stays authoritative. Accept
        // both the documented questionText key and the legacy question key.
        String questionText = payload.get("questionText") instanceof String q && !q.isBlank()
                ? q : asString(payload.get("question"), "question");
        String purpose = asString(payload.get("purpose"), "purpose");
        boolean allowFreeAnswer = payload.get("allowFreeAnswer") instanceof Boolean b && b;
        List<NodeOption> options = parseOptions(payload.get("options"));

        Node node = agentGraphMutationService.executeNodeCreation(
                context.projectId(), context.routeId(), context.anchorNodeId(),
                new AgentGraphMutationService.InteractionNode(
                        questionText, purpose, options, allowFreeAnswer));

        return new ActionResult("CREATE_NODE", node.id(), null, null);
    }

    private ActionResult executeRespondToUser(ActionProposal proposal) {
        String message = asString(proposal.payload().get("message"), "message");
        return new ActionResult("RESPOND_TO_USER", null, null, message);
    }

    private ActionResult executeWait() {
        return new ActionResult("WAIT", null, null, null);
    }

    private ActionResult executeDeferredMutation(ActionProposal proposal) {
        throw new UnsupportedOperationException(
                "Action family " + proposal.actionFamily()
                        + " requires confirmation and is not yet executable in Stage B");
    }

    private ActionResult executeUnsupported(ActionProposal proposal) {
        throw new UnsupportedOperationException(
                "Action family " + proposal.actionFamily()
                        + " is not supported in Stage B (no capability/artifact runtime)");
    }

    @SuppressWarnings("unchecked")
    private List<NodeOption> parseOptions(Object optionsObj) {
        if (!(optionsObj instanceof List<?> optionList)) {
            return List.of();
        }
        List<NodeOption> result = new ArrayList<>();
        for (Object item : optionList) {
            if (item instanceof Map<?, ?> map) {
                String label = asString(map.get("label"), "option.label");
                result.add(new NodeOption(UUID.randomUUID(), label, null));
            }
        }
        return result;
    }

    private String asString(Object value, String name) {
        if (value instanceof String s) {
            return s;
        }
        return null;
    }
}
