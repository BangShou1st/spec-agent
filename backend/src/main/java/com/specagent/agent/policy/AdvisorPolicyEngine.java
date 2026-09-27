package com.specagent.agent.policy;

import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.capability.SideEffectClass;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文件名:AdvisorPolicyEngine.java
 *
 * 用途:Advisor 模式的策略引擎,对照 Runtime 事实评估动作提案,
 * 决定执行授权。置信度绝不是授权信号——只有变更范围、生命周期状态、
 * 能力副作用类别和图不变量才驱动决策。
 *
 * 五种变更类别:
 * 1. READ_ONLY_INTERNAL —— 自动执行(WAIT、RESPOND_TO_USER、只读能力调用)
 * 2. VISIBLE_GRAPH_MUTATION —— 仅追加式续写自动执行;分支/历史相关变更要求确认
 * 3. CONFIRMED_INTENT_CHANGE —— 一律要求确认(包括 agent 撰写的
 *       KNOWLEDGE/DECISION 节点、local-durable 能力和制品生成)
 * 1. DESTRUCTIVE_OR_HISTORY —— 一律要求确认
 * 2. EXTERNAL_SIDE_EFFECT —— 拒绝,除非存在显式授权的外部能力策略
 *
 * 契约闭合不变量:响应校验器接受的每个动作族都会在这里得到确定性决策,
 * 且每个被归类为"要求确认"的动作族,在接受后必须能被
 * {@code ProposalAcceptanceService} 执行。当前阶段没有 Runtime 执行路径的
 * 动作族(UPDATE_NODE、CREATE_ROUTE、GENERATE_ARTIFACT、CONTINUATION 连接)
 * 一律直接拒绝,使"可点击但不可执行"的提案永远不会存在。
 *
 * 协作:由决策循环在执行前调用,决定 auto-execute / require-confirmation / deny。
 */
@Component
public class AdvisorPolicyEngine {

    private final RouteRepository routeRepository;
    private final CapabilityRegistry capabilityRegistry;
    private final com.specagent.workspace.node.NodeRepository nodeRepository;

    public AdvisorPolicyEngine(RouteRepository routeRepository,
                               CapabilityRegistry capabilityRegistry,
                               com.specagent.workspace.node.NodeRepository nodeRepository) {
        this.routeRepository = routeRepository;
        this.capabilityRegistry = capabilityRegistry;
        this.nodeRepository = nodeRepository;
    }

    /**
     * 评估给定提案在 Advisor 模式下是自动执行、要求确认,还是被拒绝。
     */
    public PolicyDecision evaluate(ActionProposal proposal,
                                   ActionExecutionContext context) {
        ActionFamily family = ActionFamily.fromCode(proposal.actionFamily());
        if (family == ActionFamily.INVOKE_CAPABILITY) {
            return evaluateCapabilityInvocation(proposal);
        }
        String unsupportedReason = unsupportedFamilyReason(proposal, family);
        if (unsupportedReason != null) {
            // 契约闭合:没有可执行 Runtime 命令路径的动作族绝不能变成
            // PROPOSED 提案——接受它必然无条件失败。策略层面的唯一一致
            // 答案就是拒绝,因此这些动作族根本不会创建提案。
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
     * 当前阶段动作族在用户接受后没有执行路径时返回非 null:尚无 Runtime
     * 命令层实现它,因此要求确认只会产出一个"可点击但必然失败"的提案。
     * 必须与 {@code ProposalAcceptanceService} 保持同步:不在此列表中且被
     * 归类为要求确认的动作族,都必须能在接受时执行。
     */
    private String unsupportedFamilyReason(ActionProposal proposal, ActionFamily family) {
        return switch (family) {
            case UPDATE_NODE -> "节点更新在本阶段没有可执行的运行时命令，提案被拒绝";
            case CREATE_ROUTE -> "路线创建在本阶段没有可执行的运行时命令，提案被拒绝";
            case GENERATE_ARTIFACT -> "制品生成运行时尚未接入，提案被拒绝";
            // CONTINUATION 拓扑由 continuation 命令负责(仅追加的血缘不变量);
            // acceptance 只执行 SEMANTIC 关系,因此 CONTINUATION 提案
            // 是不可执行的。
            case CONNECT_NODE -> "CONTINUATION".equals(proposal.payload().get("relationClass"))
                    ? "CONTINUATION 连接必须通过 continuation 命令执行，提案被拒绝"
                    : null;
            case CREATE_NODE, REQUEST_USER_INPUT, RESPOND_TO_USER, INVOKE_CAPABILITY, WAIT -> null;
        };
    }

    /**
     * 判断现在确认该提案是否会产生一个 {@code ProposalAcceptanceService}
     * 之后确实能执行的 PROPOSED 提案。镜像接受时的 stale 规则:创建节点的
     * 动作族只有在锚点仍是路由 tip 时才能执行,因此非 tip 锚点会产生一个
     * 看似可接受、但每次接受都因 stale 而失败的提案——这类提案不应创建。
     *
     * 创建提案的调用方(answer 周期的确认分支与 node-query 的变更降级)
     * 必须在持久化待决提案之前调用本方法。
     */
    public boolean canProduceAcceptableProposal(ActionProposal proposal,
                                                ActionExecutionContext context) {
        return switch (ActionFamily.fromCode(proposal.actionFamily())) {
            case WAIT, RESPOND_TO_USER:
                // 只读动作族被自动执行,根本不会变成提案。
                yield false;
            case UPDATE_NODE, CREATE_ROUTE, GENERATE_ARTIFACT:
                // 本阶段没有执行路径——策略直接拒绝。
                yield false;
            case CONNECT_NODE:
                // 接受时只有 SEMANTIC 关系可执行。
                yield "SEMANTIC".equals(proposal.payload().get("relationClass"))
                        && endpointsLive(proposal);
            case CREATE_NODE, REQUEST_USER_INPUT:
                // 只有锚点是活路由 tip 时才能在接受后执行
                // (镜像 ProposalAcceptanceService 的 stale 规则)。
                yield isAppendOnlyContinuation(proposal, context);
            case INVOKE_CAPABILITY:
                // 只有 local-durable 能力会确认成提案;
                // 未知 id 与外部副作用类别被拒绝。
                yield evaluateCapabilityInvocation(proposal).requiresConfirmation();
        };
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
            com.specagent.workspace.node.Node node = nodeRepository.findById(
                    UUID.fromString(ref.substring(5))).orElse(null);
            return node != null && !node.isRetracted();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 能力调用按 Runtime 持有的描述符副作用类别分级——绝不按模型置信度
     * 或一刀切规则。
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
        return switch (ActionFamily.fromCode(proposal.actionFamily())) {
            case WAIT, RESPOND_TO_USER -> MutationClass.READ_ONLY_INTERNAL;
            case REQUEST_USER_INPUT, CREATE_NODE -> classifyGraphMutation(proposal, context);
            case UPDATE_NODE, CONNECT_NODE, CREATE_ROUTE ->
                    MutationClass.CONFIRMED_INTENT_CHANGE;
            // 制品生成是本地持久化输出(不是外部副作用):在制品运行时
            // 接入执行之前,需要用户确认。
            case GENERATE_ARTIFACT ->
                    MutationClass.CONFIRMED_INTENT_CHANGE;
            // INVOKE_CAPABILITY 永远不会走到 classify():它按描述符的
            // 副作用类别被分发给 evaluateCapabilityInvocation。
            case INVOKE_CAPABILITY -> throw new IllegalStateException(
                    "Capability invocation must be classified by its descriptor");
        };
    }

    private MutationClass classifyGraphMutation(ActionProposal proposal,
                                                ActionExecutionContext context) {
        // 模型撰写的 DECISION 是已确认的产品意图,不只是追加式的 UI 内容。
        // 即使它锚定在活路由 tip 上,也必须保持提案状态直到用户显式接受。
        // 这是冲突解决委托的 Runtime 兜底;自然语言形式的模型置信度
        // 绝不能静默授权一次决策。
        if (isDecisionNode(proposal)) {
            return MutationClass.CONFIRMED_INTENT_CHANGE;
        }
        if (isAppendOnlyContinuation(proposal, context)) {
            return MutationClass.VISIBLE_GRAPH_MUTATION;
        }
        return MutationClass.CONFIRMED_INTENT_CHANGE;
    }

    private boolean isDecisionNode(ActionProposal proposal) {
        return ActionFamily.fromCode(proposal.actionFamily()) == ActionFamily.CREATE_NODE
                && "KNOWLEDGE".equals(proposal.payload().get("kind"))
                && "DECISION".equals(proposal.payload().get("subtype"));
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
     * 仅追加式续写是只向路由追加内容的变更:要么在当前路由 tip 上
     * 新增子节点(锚点等于 tip),要么在尚无 tip 的路由上添加引导根节点
     * (两者均为 null)。任何会触及既有已确认意图的变更都不是仅追加式。
     * 非空路由上的 null 锚点绝不是仅追加式(fail-closed)。
     */
    private boolean isAppendOnlyContinuation(ActionProposal proposal,
                                             ActionExecutionContext context) {
        Route route = routeRepository.findById(context.routeId()).orElse(null);
        if (route == null) {
            return false;
        }
        UUID tipNodeId = route.tipNodeId();
        if (tipNodeId == null) {
            // 路由引导:追加第一个根节点只增加血缘,不改变任何既有意图。
            return context.anchorNodeId() == null;
        }
        return tipNodeId.equals(context.anchorNodeId());
    }
}
