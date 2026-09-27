package com.specagent.agent.runtime;

import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalAlreadyDecidedException;
import com.specagent.agent.runtime.ProposalAcceptanceService;

import com.specagent.agent.runtime.AgentRunRepository;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.ActionExecutor;
import com.specagent.agent.action.ActionResult;
import com.specagent.agent.action.StaleProposalException;
import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.runtime.ContinuationCheckRepository;
import com.specagent.agent.runtime.ContinuationDispatchService;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.agent.snapshot.StaleContextChecker;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.graph.GraphOperation;
import com.specagent.workspace.graph.GraphOperationRepository;
import com.specagent.workspace.graph.NodeRelation;
import com.specagent.workspace.graph.NodeRelationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:ProposalAcceptanceService.java
 *
 * 用途:执行用户已接受的 Advisor proposal。Acceptance 就是 Advisor 模式
 * 所要求的"确认"步骤:存储的 proposal 会先对照当前图事实重新校验
 * (stale 锚点与已消失的端点会被拒绝,绝不静默 rebase 到新状态),再经
 * runtime 命令层执行,并以可追溯到该 proposal 的 agent 变更身份写入类型化
 * 操作日志。
 */
@Service
public class ProposalAcceptanceService {

    private static final Logger LOG = LoggerFactory.getLogger(ProposalAcceptanceService.class);

    private final AgentProposalService proposalService;
    private final ActionExecutor actionExecutor;
    private final GraphCommandService graphCommandService;
    private final GraphOperationRepository operationRepository;
    private final NodeRepository nodeRepository;
    private final RouteRepository routeRepository;
    private final ProjectRepository projectRepository;
    private final StaleContextChecker staleContextChecker;
    private final ContextSnapshotRepository contextSnapshotRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunEventService eventService;
    private final ContinuationCheckRepository checkRepository;
    private final ContinuationDispatchService continuationDispatch;

    public ProposalAcceptanceService(AgentProposalService proposalService,
                                     ActionExecutor actionExecutor,
                                     GraphCommandService graphCommandService,
                                     GraphOperationRepository operationRepository,
                                     NodeRepository nodeRepository,
                                     RouteRepository routeRepository,
                                     ProjectRepository projectRepository,
                                     StaleContextChecker staleContextChecker,
                                     ContextSnapshotRepository contextSnapshotRepository,
                                     AgentRunRepository agentRunRepository,
                                     AgentRunEventService eventService,
                                     ContinuationCheckRepository checkRepository,
                                     ContinuationDispatchService continuationDispatch) {
        this.proposalService = proposalService;
        this.actionExecutor = actionExecutor;
        this.graphCommandService = graphCommandService;
        this.operationRepository = operationRepository;
        this.nodeRepository = nodeRepository;
        this.routeRepository = routeRepository;
        this.projectRepository = projectRepository;
        this.staleContextChecker = staleContextChecker;
        this.contextSnapshotRepository = contextSnapshotRepository;
        this.agentRunRepository = agentRunRepository;
        this.eventService = eventService;
        this.checkRepository = checkRepository;
        this.continuationDispatch = continuationDispatch;
    }

    public record AcceptedProposalResult(String actionFamily, UUID producedNodeId, UUID relationId,
                                           UUID originRunId) {
    }

    /**
     * 在一个事务内接受并执行 pending proposal。执行失败时 proposal 保持
     * PROPOSED 状态,用户可以在底层问题解决后重试。
     *
     * 加锁顺序为 project → proposal → graph:先取 project 行锁(从
     * 不可变的 proposal 元数据解析出项目之后),再锁定并重读 proposal 行,
     * 最后做图变更。这与所有其他图写入方的"project 优先"顺序
     * ({@link GraphCommandService}、{@code UndoRedoService})一致,因此
     * acceptance 绝不会持着 proposal 锁又按相反顺序等 project 锁
     * (不存在 proposal→project 的死锁路径)。
     */
    @Transactional
    public AcceptedProposalResult acceptAndExecute(UUID proposalId, String decidedBy) {
        // 1. 从不可变的 proposal 元数据解析项目(不加锁)。
        AgentProposal metadata = proposalService.getProposal(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));
        UUID projectId = metadata.projectId();
        // 2. 与项目内所有其他图写入方串行化。
        projectRepository.lockById(projectId);
        // 3. 取到 project 锁后,再锁定并重读 proposal 行。唯一的"赢家仲裁"
        //    发生在这里:行锁会持有到本事务提交或回滚为止,并发 racing 的
        //    accept/reject/expire 会排在它后面,然后再观察已提交的结果。
        AgentProposal stored = proposalService.getProposalForUpdate(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));
        if (stored.status() != ProposalStatus.PROPOSED) {
            throw new ProposalAlreadyDecidedException(stored.status().code());
        }
        if (!stored.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Proposal project changed between reads: " + proposalId);
        }

        ActionProposal proposal = rebuildActionProposal(stored);
        // 冻结"可变来源"的过期判定:如果产生该 proposal 时模型可见的内容
        // 后来发生了变化,则本次 acceptance 视为过期。
        contextSnapshotRepository.findById(stored.baseContextSnapshotId())
                .ifPresent(snapshot -> {
                    if (!isReadOnlyFamily(proposal)) {
                        staleContextChecker.verifyMutableSourcesStillFresh(snapshot);
                    }
                });
        validateStillFresh(proposal, stored);

        ActionFamily family = ActionFamily.fromCode(stored.actionFamily());
        AcceptedProposalResult result = switch (family) {
            case CREATE_NODE, REQUEST_USER_INPUT -> executeNodeAction(proposal, stored);
            case CONNECT_NODE -> executeConnectNode(proposal, stored);
            case INVOKE_CAPABILITY -> executeCapabilityInvocation(proposal, stored);
            // UPDATE_NODE / CREATE_ROUTE / GENERATE_ARTIFACT / CONTINUATION
            // 连接绝不会以 PROPOSED 状态走到这里:policy 在创建之前就拒绝了
            // 它们,因为本阶段没有命令层会执行它们。下面的防御性失败保持
            // fail-closed。
            case UPDATE_NODE, CREATE_ROUTE, RESPOND_TO_USER, GENERATE_ARTIFACT, WAIT ->
                    throw new UnsupportedOperationException(
                            "Action family " + stored.actionFamily()
                                    + " cannot be executed on acceptance in this stage");
        };

        proposalService.acceptProposal(proposalId, decidedBy);
        // capability 调用不产生图实体;节点/关系 family 总是恰好产生一个。
        List<UUID> producedRefs = result.producedNodeId() != null
                ? List.of(result.producedNodeId())
                : result.relationId() != null ? List.of(result.relationId()) : List.of();
        // 只有带来真实图效果的 acceptance 才记为不可逆的
        // ACCEPT_AGENT_PROPOSAL 屏障。无效果的 acceptance(例如
        // INVOKE_CAPABILITY)绝不能锁死整个 undo 历史——它可能触发的图变更
        // (agent 节点/关系创建)已经以各自可逆的 AGENT 操作进入了日志。
        if (!producedRefs.isEmpty()) {
            operationRepository.append(stored.projectId(), GraphOperation.Actor.AGENT,
                    GraphOperation.Type.ACCEPT_AGENT_PROPOSAL,
                    producedRefs,
                    Map.of(), Map.of("actionFamily", stored.actionFamily()),
                    "proposal:" + proposalId);
        }

        // Slice 6:原始 run 在同一事务里获得持久化效果引用(不动 status/trace
        // ——它早已终态化)以及一条 ACCEPTANCE_EXECUTED 事件,其 continuation
        // check 也随之重新打开。协调器会基于这些持久化事实重新判定该 run:
        // ACCEPTED(不再是 PARKED_APPROVAL)加上可消费的效果(图节点)会产生
        // 子 run;INTERACTION 输出仍作为外部边界挂起;无效果的 acceptance
        // 仍判为 NO_EFFECT。acceptance 本身绝不强制续跑。
        //
        // 原始 run id 只是历史遗留的创建提示:外部创建路径可能携带一个没有
        // 对应持久化 run 行的 run id(仅 proposal 的流程)。只有真正持久化过
        // 的 run 才算 origin:续跑效果与返回的 originRunId 共用这一个闸门,
        // API 绝不会把幽灵 run 交给前端去轮询。
        UUID persistedOriginRunId = stored.runId() != null
                && agentRunRepository.findById(stored.runId()).isPresent()
                ? stored.runId() : null;
        if (persistedOriginRunId != null) {
            if (result.producedNodeId() != null) {
                agentRunRepository.attachApprovalProducedNode(
                        persistedOriginRunId, result.producedNodeId());
            }
            eventService.append(persistedOriginRunId, AgentRunPhase.COMPLETED,
                    "ACCEPTANCE_EXECUTED", Map.of(
                            "proposalId", proposalId.toString(),
                            "actionFamily", stored.actionFamily()));
            checkRepository.request(persistedOriginRunId);
            dispatchContinuationAfterCommit(persistedOriginRunId);
        }
        return new AcceptedProposalResult(result.actionFamily(), result.producedNodeId(),
                result.relationId(), persistedOriginRunId);
    }

    /**
     * 重新打开的 continuation check 的尽力而为快速通道投递。acceptance
     * 事务已提交该请求;派发失败只记日志并保持 pending,交给恢复扫描器
     * ——绝不让用户的 acceptance 调用失败。
     */
    private void dispatchContinuationAfterCommit(UUID runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                continuationDispatch.process(runId);
                            } catch (RuntimeException ex) {
                                LOG.warn("Continuation dispatch deferred for run {}: {}",
                                        runId, ex.getMessage());
                            }
                        }
                    });
        } else {
            try {
                continuationDispatch.process(runId);
            } catch (RuntimeException ex) {
                LOG.warn("Continuation dispatch deferred for run {}: {}",
                        runId, ex.getMessage());
            }
        }
    }

    /**
     * 从持久化记录重建线上协议层 proposal 视图。持久化的 payload 就是
     * 已通过校验的模型 payload;身份字段从 runtime 自有的列恢复。
     */
    private ActionProposal rebuildActionProposal(AgentProposal stored) {
        return new ActionProposal(
                stored.actionFamily(),
                stored.payload(),
                stored.baseContextSnapshotId(),
                stored.baseContextHash(),
                List.of(),
                stored.id(),
                stored.idempotencyKey(),
                stored.anchorRefs());
    }

    /**
     * 对照当前图事实做过期校验。创建节点的 proposal 要求其锚点仍是
     * route tip;关系的 proposal 要求两个端点都仍存在且未被撤销。
     */
    private void validateStillFresh(ActionProposal proposal, AgentProposal stored) {
        switch (ActionFamily.fromCode(stored.actionFamily())) {
            case CREATE_NODE, REQUEST_USER_INPUT -> {
                Route route = routeRepository.findById(stored.routeId())
                        .orElseThrow(() -> new StaleProposalException(
                                "Proposal route no longer exists: " + stored.routeId()));
                UUID anchorNodeId = firstNodeRef(proposal);
                // 锚点语义与事务性图动作边界一致:空锚点只在 route 仍为空
                // (引导根节点)时合法;非空锚点必须仍是当前活跃的 route tip。
                // 绝不回退成"追加到现在不管是谁的 tip"——那会静默 rebase
                // 当初的决策。
                if (anchorNodeId == null) {
                    if (route.tipNodeId() != null) {
                        throw new StaleProposalException(
                                "Proposal carries no anchor but the route already has a tip; "
                                        + "the graph has moved on. Trigger a new decision instead "
                                        + "of accepting this proposal.");
                    }
                } else if (!anchorNodeId.equals(route.tipNodeId())) {
                    throw new StaleProposalException(
                            "Proposal anchor is no longer the route tip; the graph has moved on. "
                                    + "Trigger a new decision instead of accepting this proposal.");
                }
            }
            case CONNECT_NODE -> {
                UUID sourceId = nodeRefFrom(stored.payload().get("sourceRef"));
                UUID targetId = nodeRefFrom(stored.payload().get("targetRef"));
                requireLiveNode(sourceId);
                requireLiveNode(targetId);
            }
            case INVOKE_CAPABILITY -> {
                // capability 参数可能引用图节点;这些 ref 在接受时必须仍然
                // 存活,与 proposal 创建时通过的那次线上校验保持一致。
                if (stored.payload().get("arguments") instanceof Map<?, ?> arguments) {
                    for (Object value : arguments.values()) {
                        if (value instanceof String ref && ref.startsWith("node:")) {
                            requireLiveNode(UUID.fromString(ref.substring(5)));
                        }
                    }
                }
            }
            case UPDATE_NODE, CREATE_ROUTE, RESPOND_TO_USER, GENERATE_ARTIFACT, WAIT -> {
                // 本阶段其他 family 没有新鲜度规则。
            }
        }
    }

    private AcceptedProposalResult executeNodeAction(ActionProposal proposal, AgentProposal stored) {
        UUID anchorNodeId = firstNodeRef(proposal);
        ActionExecutionContext context = new ActionExecutionContext(
                stored.runId(), stored.projectId(), stored.routeId(),
                stored.baseContextSnapshotId(), anchorNodeId, null, null);
        ActionResult result = actionExecutor.execute(proposal, context);
        return new AcceptedProposalResult(stored.actionFamily(), result.producedNodeId(), null,
                null);
    }

    private AcceptedProposalResult executeConnectNode(ActionProposal proposal, AgentProposal stored) {
        Object relationClass = stored.payload().get("relationClass");
        if (!"SEMANTIC".equals(relationClass)) {
            throw new UnsupportedOperationException(
                    "Only SEMANTIC relations are executable on acceptance; CONTINUATION "
                            + "connections must go through continuation commands");
        }
        NodeRelation relation = graphCommandService.createSemanticRelation(
                stored.projectId(),
                nodeRefFrom(stored.payload().get("sourceRef")),
                nodeRefFrom(stored.payload().get("targetRef")),
                NodeRelationType.fromCode((String) stored.payload().get("relationType")),
                NodeRelation.Origin.AGENT,
                stored.id(),
                stored.runId());
        return new AcceptedProposalResult(stored.actionFamily(), null, relation.id(), null);
    }

    /**
     * 本地持久化的 capability 调用,通过与自动执行路径相同的 action executor
     * 执行(它会携带 runtime 自有的幂等键路由进 capability runtime)
     * ——acceptance 绝不复制一份执行逻辑。只读 capability 不会成为 proposal
     * (policy 自动执行它们);外部副作用类在创建前就被拒绝。
     */
    private AcceptedProposalResult executeCapabilityInvocation(ActionProposal proposal,
                                                               AgentProposal stored) {
        UUID anchorNodeId = firstNodeRef(proposal);
        ActionExecutionContext context = new ActionExecutionContext(
                stored.runId(), stored.projectId(), stored.routeId(),
                stored.baseContextSnapshotId(), anchorNodeId, null, null);
        ActionResult result = actionExecutor.execute(proposal, context);
        return new AcceptedProposalResult(stored.actionFamily(), result.producedNodeId(), null,
                null);
    }

    private boolean isReadOnlyFamily(ActionProposal proposal) {
        try {
            com.specagent.agent.protocol.ActionFamily family =
                    com.specagent.agent.protocol.ActionFamily.fromCode(proposal.actionFamily());
            return family == com.specagent.agent.protocol.ActionFamily.RESPOND_TO_USER
                    || family == com.specagent.agent.protocol.ActionFamily.WAIT;
        } catch (Exception ex) {
            return false;
        }
    }

    private UUID firstNodeRef(ActionProposal proposal) {
        return proposal.anchorRefs().stream()
                .filter(ref -> ref.startsWith("node:"))
                .map(ref -> nodeRefFrom(ref))
                .findFirst()
                .orElse(null);
    }

    private UUID nodeRefFrom(Object ref) {
        if (!(ref instanceof String value) || !value.startsWith("node:")) {
            throw new StaleProposalException("Relation endpoint is not a node ref: " + ref);
        }
        return UUID.fromString(value.substring(5));
    }

    private void requireLiveNode(UUID nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new StaleProposalException(
                        "Relation endpoint no longer exists: " + nodeId));
        if (node.isRetracted()) {
            throw new StaleProposalException(
                    "Relation endpoint has been retracted: " + nodeId);
        }
    }
}
