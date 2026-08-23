package com.specagent.agent.policy;

import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.capability.SideEffectClass;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Advisor-mode policy engine that evaluates action proposals against runtime
 * facts to determine execution authorization. Confidence is never an
 * authorization signal — only mutation scope, lifecycle state, capability
 * side-effect class, and graph invariants drive the decision.
 *
 * <p>Five mutation classes are distinguished:
 * <ol>
 *   <li>READ_ONLY_INTERNAL — auto-execute (WAIT, RESPOND_TO_USER, read-only
 *       capability invocations)</li>
 *   <li>VISIBLE_GRAPH_MUTATION — auto-execute for append-only continuation;
 *       confirm for branch/history-adjacent mutations</li>
 *   <li>CONFIRMED_INTENT_CHANGE — always confirm (includes local-durable
 *       capabilities and artifact generation)</li>
 *   <li>DESTRUCTIVE_OR_HISTORY — always confirm</li>
 *   <li>EXTERNAL_SIDE_EFFECT — deny unless an explicitly authorized external
 *       capability policy exists</li>
 * </ol>
 */
@Component
public class AdvisorPolicyEngine {

    private final RouteRepository routeRepository;
    private final CapabilityRegistry capabilityRegistry;

    public AdvisorPolicyEngine(RouteRepository routeRepository,
                               CapabilityRegistry capabilityRegistry) {
        this.routeRepository = routeRepository;
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * Evaluates whether the given proposal may be auto-executed, requires
     * confirmation, or is denied under Advisor mode.
     */
    public PolicyDecision evaluate(ActionProposal proposal,
                                   ActionExecutionContext context) {
        if ("INVOKE_CAPABILITY".equals(proposal.actionFamily())) {
            return evaluateCapabilityInvocation(proposal);
        }
        MutationClass classification = classify(proposal, context);
        return switch (classification) {
            case READ_ONLY_INTERNAL -> PolicyDecision.autoExecute(classification);
            case VISIBLE_GRAPH_MUTATION -> evaluateVisibleMutation(proposal, context, classification);
            case CONFIRMED_INTENT_CHANGE ->
                    PolicyDecision.requireConfirmation(classification);
            case DESTRUCTIVE_OR_HISTORY ->
                    PolicyDecision.requireConfirmation(classification);
            case EXTERNAL_SIDE_EFFECT -> PolicyDecision.deny(classification,
                    "外部能力副作用需要显式授权配置，当前不可自动执行");
        };
    }

    /**
     * Capability invocations are classified by the runtime-owned descriptor
     * side-effect class — never by model confidence or a blanket rule.
     */
    private PolicyDecision evaluateCapabilityInvocation(ActionProposal proposal) {
        Object id = proposal.payload().get("capabilityId");
        if (!(id instanceof String capabilityId) || capabilityId.isBlank()) {
            return PolicyDecision.deny(MutationClass.EXTERNAL_SIDE_EFFECT,
                    "提案未声明合法的 capabilityId，拒绝执行");
        }
        CapabilityDescriptor descriptor = capabilityRegistry.findDescriptor(capabilityId)
                .orElse(null);
        if (descriptor == null) {
            return PolicyDecision.deny(MutationClass.EXTERNAL_SIDE_EFFECT,
                    "未知能力标识: " + capabilityId);
        }
        return switch (descriptor.sideEffectClass()) {
            case NONE -> PolicyDecision.autoExecute(MutationClass.READ_ONLY_INTERNAL);
            case LOCAL_DURABLE ->
                    PolicyDecision.requireConfirmation(MutationClass.CONFIRMED_INTENT_CHANGE);
            case EXTERNAL_REVERSIBLE, EXTERNAL_IRREVERSIBLE ->
                    PolicyDecision.deny(MutationClass.EXTERNAL_SIDE_EFFECT,
                            "外部副作用能力（" + descriptor.sideEffectClass().code() + "）需要显式授权策略");
        };
    }

    private MutationClass classify(ActionProposal proposal,
                                   ActionExecutionContext context) {
        return switch (proposal.actionFamily()) {
            case "WAIT", "RESPOND_TO_USER" -> MutationClass.READ_ONLY_INTERNAL;
            case "REQUEST_USER_INPUT", "CREATE_NODE" -> classifyGraphMutation(proposal, context);
            case "UPDATE_NODE", "CONNECT_NODE", "CREATE_ROUTE" ->
                    MutationClass.CONFIRMED_INTENT_CHANGE;
            // Artifact generation is local durable output (not an external
            // side effect): it needs confirmation while no artifact runtime
            // is wired for execution.
            case "GENERATE_ARTIFACT" ->
                    MutationClass.CONFIRMED_INTENT_CHANGE;
            // INVOKE_CAPABILITY never reaches classify(): it is dispatched to
            // evaluateCapabilityInvocation by descriptor side-effect class.
            // Unknown families never reach the policy engine either (the
            // response validator rejects them first); if one does, fail toward
            // the strictest non-denying class: explicit confirmation.
            default -> MutationClass.CONFIRMED_INTENT_CHANGE;
        };
    }

    private MutationClass classifyGraphMutation(ActionProposal proposal,
                                                ActionExecutionContext context) {
        if (isAppendOnlyContinuation(proposal, context)) {
            return MutationClass.VISIBLE_GRAPH_MUTATION;
        }
        return MutationClass.CONFIRMED_INTENT_CHANGE;
    }

    private PolicyDecision evaluateVisibleMutation(ActionProposal proposal,
                                                   ActionExecutionContext context,
                                                   MutationClass classification) {
        if (isAppendOnlyContinuation(proposal, context)) {
            return PolicyDecision.autoExecute(classification);
        }
        return PolicyDecision.requireConfirmation(classification);
    }

    /**
     * An append-only continuation is a mutation that adds a new child node
     * to the current route tip without changing any existing confirmed intent.
     * The anchor must be the current route tip and the action must be a
     * forward-adding family.
     */
    private boolean isAppendOnlyContinuation(ActionProposal proposal,
                                             ActionExecutionContext context) {
        if (context.anchorNodeId() == null) {
            return false;
        }
        Route route = routeRepository.findById(context.routeId()).orElse(null);
        if (route == null) {
            return false;
        }
        UUID tipNodeId = route.tipNodeId();
        if (tipNodeId == null) {
            return false;
        }
        return tipNodeId.equals(context.anchorNodeId());
    }
}
