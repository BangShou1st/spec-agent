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
 *
 * <p>Contract closure invariant: every family the response validator accepts
 * gets a deterministic decision here, and every family classified as
 * requiring confirmation must be executable by
 * {@code ProposalAcceptanceService} after acceptance. Families with no
 * runtime execution path in the current stage (UPDATE_NODE, CREATE_ROUTE,
 * GENERATE_ARTIFACT, CONTINUATION connections) are denied outright so a
 * clickable-but-unexecutable proposal can never exist.
 */
@Component
public class AdvisorPolicyEngine {

    private final RouteRepository routeRepository;
    private final CapabilityRegistry capabilityRegistry;
    private final com.specagent.node.NodeRepository nodeRepository;

    public AdvisorPolicyEngine(RouteRepository routeRepository,
                               CapabilityRegistry capabilityRegistry,
                               com.specagent.node.NodeRepository nodeRepository) {
        this.routeRepository = routeRepository;
        this.capabilityRegistry = capabilityRegistry;
        this.nodeRepository = nodeRepository;
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
        String unsupportedReason = unsupportedFamilyReason(proposal);
        if (unsupportedReason != null) {
            // Contract closure: a family without an executable runtime command
            // path must never become a PROPOSED proposal — accepting it would
            // fail unconditionally. Deny is the single consistent answer from
            // policy, so proposal creation never happens for these families.
            return PolicyDecision.deny(MutationClass.CONFIRMED_INTENT_CHANGE,
                    unsupportedReason);
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
     * Non-null when the action family has no execution path behind user
     * acceptance in the current stage: no runtime command layer implements
     * it yet, so requiring confirmation would produce a proposal that is
     * clickable but guaranteed to fail. Keep this in lockstep with
     * {@code ProposalAcceptanceService}: every family NOT listed here and
     * classified as requiring confirmation must be executable on acceptance.
     */
    private String unsupportedFamilyReason(ActionProposal proposal) {
        return switch (proposal.actionFamily()) {
            case "UPDATE_NODE" -> "节点更新在本阶段没有可执行的运行时命令，提案被拒绝";
            case "CREATE_ROUTE" -> "路线创建在本阶段没有可执行的运行时命令，提案被拒绝";
            case "GENERATE_ARTIFACT" -> "制品生成运行时尚未接入，提案被拒绝";
            // CONTINUATION topology is owned by the continuation commands
            // (append-only lineage invariants); acceptance only executes
            // SEMANTIC relations, so a CONTINUATION proposal would be
            // unexecutable.
            case "CONNECT_NODE" -> "CONTINUATION".equals(proposal.payload().get("relationClass"))
                    ? "CONTINUATION 连接必须通过 continuation 命令执行，提案被拒绝"
                    : null;
            default -> null;
        };
    }

    /**
     * True when confirming this proposal now would produce a PROPOSED
     * proposal that {@code ProposalAcceptanceService} can actually execute
     * later. This mirrors the acceptance-time staleness rules: node-creating
     * families execute only while their anchor is still the route tip, so a
     * non-tip anchor would create an acceptable-looking proposal whose every
     * acceptance attempt fails as stale — those must not be created.
     *
     * <p>Proposal-creating call sites (the answer cycle's confirmation branch
     * and the node-query mutation downgrade) must consult this before
     * persisting a pending proposal.
     */
    public boolean canProduceAcceptableProposal(ActionProposal proposal,
                                                ActionExecutionContext context) {
        switch (proposal.actionFamily()) {
            case "WAIT", "RESPOND_TO_USER":
                // Read-only families are auto-executed; they never become
                // proposals in the first place.
                return false;
            case "UPDATE_NODE", "CREATE_ROUTE", "GENERATE_ARTIFACT":
                // No execution path in this stage — policy denies them.
                return false;
            case "CONNECT_NODE":
                // Only SEMANTIC relations are executable on acceptance.
                return "SEMANTIC".equals(proposal.payload().get("relationClass"))
                        && endpointsLive(proposal);
            case "CREATE_NODE", "REQUEST_USER_INPUT":
                // Executable on acceptance only while the anchor is the live
                // route tip (mirrors ProposalAcceptanceService staleness).
                return isAppendOnlyContinuation(proposal, context);
            case "INVOKE_CAPABILITY":
                // Only local-durable capabilities confirm into proposals;
                // unknown ids and external side-effect classes are denied.
                return evaluateCapabilityInvocation(proposal).requiresConfirmation();
            default:
                return false;
        }
    }

    private boolean endpointsLive(ActionProposal proposal) {
        Object source = proposal.payload().get("sourceRef");
        Object target = proposal.payload().get("targetRef");
        return source instanceof String sourceRef && sourceRef.startsWith("node:")
                && target instanceof String targetRef && targetRef.startsWith("node:")
                && isLiveNodeRef(sourceRef) && isLiveNodeRef(targetRef);
    }

    private boolean isLiveNodeRef(String ref) {
        try {
            com.specagent.node.Node node = nodeRepository.findById(
                    UUID.fromString(ref.substring(5))).orElse(null);
            return node != null && !node.isRetracted();
        } catch (IllegalArgumentException ex) {
            return false;
        }
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
     * An append-only continuation is a mutation that only appends to the
     * route: either a new child node at the current route tip (anchor equals
     * tip) or the bootstrap root node on a route that has no tip yet (both
     * null). Anything that would touch existing confirmed intent is not
     * append-only. A null anchor over a non-empty route is never append-only
     * (fail-closed).
     */
    private boolean isAppendOnlyContinuation(ActionProposal proposal,
                                             ActionExecutionContext context) {
        Route route = routeRepository.findById(context.routeId()).orElse(null);
        if (route == null) {
            return false;
        }
        UUID tipNodeId = route.tipNodeId();
        if (tipNodeId == null) {
            // Route bootstrap: appending the first root node adds lineage
            // without changing any existing intent.
            return context.anchorNodeId() == null;
        }
        return tipNodeId.equals(context.anchorNodeId());
    }
}
