package com.specagent.agent.runtime;

import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.gates.SpecGroundingGate;
import com.specagent.agent.gates.SpecSourceReferenceGuard;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunFailureService;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.protocol.ModelContractException;
import com.specagent.agent.protocol.AgentArtifactResponse;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.decision.ReflectionResult;
import com.specagent.agent.decision.SpecDraft;
import com.specagent.agent.gates.SpecGroundingGate;
import com.specagent.agent.gates.SpecSourceReferenceGuard;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.RunProgressRecorder;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.spec.SourceKind;
import com.specagent.workspace.spec.SourceReference;
import com.specagent.workspace.spec.SpecSection;
import com.specagent.workspace.spec.SpecSnapshot;
import com.specagent.workspace.spec.SpecSnapshotService;
import com.specagent.workspace.spec.UnresolvedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:ArtifactCycleService.java
 *
 * 用途:规格(artifact)生成循环的执行器——恰好 1 次 ARTIFACT_GENERATION 调用,
 * 产出一个派生的只读规格快照。此循环不改动图、也没有回答需要解析,因此没有
 * STATE_UPDATE、也没有策略链;但在任何内容落库之前,仍要跑与遗留循环相同的
 * fail-closed 落锚校验({@link SpecGroundingGate} + {@link SpecSourceReferenceGuard}),
 * 且快照携带其 run 来源信息。
 */
@Service
public class ArtifactCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactCycleService.class);

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final SpecGroundingGate specGroundingGate;
    private final SpecSourceReferenceGuard specSourceReferenceGuard;
    private final SpecSnapshotService specSnapshotService;
    private final ExecutionFence executionFence;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final AgentRunEventService eventService;
    private final RouteRepository routeRepository;
    private final com.specagent.workspace.project.ProjectRepository projectRepository;
    private final RunProgressRecorder progressRecorder;
    private final AnswerService answerService;
    private final AnswerProcessingGate answerProcessingGate;

    public ArtifactCycleService(AgentRunService agentRunService,
                                AgentRunFailureService agentRunFailureService,
                                ContextBuilder contextBuilder,
                                ContextGuard contextGuard,
                                AgentInputSnapshotBuilder snapshotBuilder,
                                AgentDecisionEngine decisionEngine,
                                SpecGroundingGate specGroundingGate,
                                SpecSourceReferenceGuard specSourceReferenceGuard,
                                SpecSnapshotService specSnapshotService,
                                ExecutionFence executionFence,
                                org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                                AgentRunEventService eventService,
                                RouteRepository routeRepository,
                                com.specagent.workspace.project.ProjectRepository projectRepository,
                                RunProgressRecorder progressRecorder,
                                AnswerService answerService,
                                AnswerProcessingGate answerProcessingGate) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.specGroundingGate = specGroundingGate;
        this.specSourceReferenceGuard = specSourceReferenceGuard;
        this.specSnapshotService = specSnapshotService;
        this.executionFence = executionFence;
        this.transactionTemplate = transactionTemplate;
        this.eventService = eventService;
        this.routeRepository = routeRepository;
        this.projectRepository = projectRepository;
        this.progressRecorder = progressRecorder;
        this.answerService = answerService;
        this.answerProcessingGate = answerProcessingGate;
    }

    /**
     * 针对 run 自身冻结的目标(路线 + 输入节点)执行一次规格快照生成。
     * 上下文快照严格按 {@code run.routeId} / {@code run.inputNodeId} 构建;
     * 绝不读取项目的 Active 路线指针来选择上下文或落库目标,因此排队中的
     * run 不会把其他路线的内容混进自己的快照。
     *
     * 任何模型调用前先做实时状态校验:run 的路线必须仍然存在、属于该
     * run 的项目、处于 OPEN 状态,且仍是项目的 ACTIVE 路线并以
     * {@code run.inputNodeId} 为 tip。若用户在 run 排队期间切换了活跃路线,
     * run 会 fail-closed(STALE),绝不生成混合或过期的规格。
     */
    public SpecGenerationOutcome generateSpec(AgentRun run) {        Route route = routeRepository.findById(run.routeId())
                .orElseThrow(() -> new IllegalStateException(
                        "Route not found: " + run.routeId()));
        if (!route.projectId().equals(run.projectId())) {
            throw new StaleRunTargetException(
                    "Run route does not belong to the run's project: " + run.routeId());
        }
        if (route.tipNodeId() == null) {
            throw new StaleRunTargetException(
                    "Run target route has no tip node: " + route.id());
        }
        if (!route.lifecycleStatus().equals(com.specagent.workspace.route.RouteLifecycleStatus.OPEN)) {
            throw new StaleRunTargetException(
                    "Run target route is no longer OPEN: " + route.id());
        }

        com.specagent.workspace.project.Project project = projectRepository.findById(run.projectId())
                .orElseThrow(() -> new IllegalStateException(
                        "Project not found: " + run.projectId()));
        boolean explicitRoute = isExplicitRouteRun(run);
        // Active 模式保留原有的 fail-closed 保证:绝不替用户已不再处理的路线
        // 生成规格。显式路线 run 则以自己的路线为目标(其 OPEN 状态与 tip
        // 已在上面检查)。
        if (!explicitRoute && !java.util.Objects.equals(project.activeRouteId(), run.routeId())) {
            throw new StaleRunTargetException(
                    "Active route changed while artifact run was queued: run route "
                            + run.routeId() + ", active route " + project.activeRouteId());
        }
        if (!java.util.Objects.equals(run.inputNodeId(), route.tipNodeId())) {
            throw new StaleRunTargetException(
                    "Run input node is no longer the target route tip: " + run.inputNodeId()
                            + " (current tip: " + route.tipNodeId() + ")");
        }
        failIfTipAnswerUnprocessed(route);

        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            // 绑定路线构建:绝不重新读取活跃路线指针。
            ContextSnapshot snapshot = contextBuilder.buildForRoute(
                    run.projectId(), run.routeId(), run.inputNodeId(),
                    run.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), snapshot.id(), trace);
            eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            if (!contextGuard.validate(snapshot, explicitRoute).accepted()) {
                throw new ModelContractException("Context guard rejected agent run");
            }

            // 纯派生:一次 artifact 调用,绝不是 STATE_UPDATE。
            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    run.id(), snapshot,
                    new AgentEvent("CONTINUE", route.tipNodeId(), null, null),
                    new DecisionBudget(1));

            trace = appendTrace(trace, "artifact_generating");
            eventService.append(run.id(), AgentRunPhase.ARTIFACT_GENERATING,
                    "ARTIFACT_GENERATION_STARTED", Map.of());
            progressRecorder.note(run.id(), AgentRunPhase.ARTIFACT_GENERATING,
                    "正在基于当前需求状态生成规格文档");
            AgentArtifactResponse response = decisionEngine.runArtifactGeneration(envelope);
            AgentArtifactResponse.ArtifactGenerationResult result = response.artifact();

            // 保留自遗留循环的落锚门,顺序与失败语义完全一致。
            SpecDraft draft = toSpecDraft(result);
            ReflectionResult grounding = specGroundingGate.validate(draft);
            trace = appendTrace(trace, "reflected:SPEC_GROUNDING");
            agentRunService.markReflected(run.id(), trace);
            if (!grounding.accepted()) {
                agentRunService.fail(run.id(),
                        appendTrace(trace, "failed:spec_grounding_rejected"));
                throw new ModelContractException("Spec grounding rejected spec draft");
            }

            List<SourceReference> sourceRefs = distinctSourceRefs(result);
            ReflectionResult sourceRefsReflection = specSourceReferenceGuard.validate(
                    run.projectId(), route.id(), snapshot, sourceRefs);
            trace = appendTrace(trace, "reflected:SOURCE_REFERENCES");
            agentRunService.markReflected(run.id(), trace);
            if (!sourceRefsReflection.accepted()) {
                agentRunService.fail(run.id(),
                        appendTrace(trace, "failed:source_references_rejected"));
                throw new ModelContractException(
                        "Spec source reference guard rejected spec draft");
            }

            List<SpecSection> sections = result.sections().stream()
                    .map(section -> SpecSection.of(section.title(), section.content()))
                    .toList();
            List<UnresolvedItem> unresolvedItems = result.unresolvedItems().stream()
                    .map(text -> UnresolvedItem.of(text, "unresolved"))
                    .toList();

            // 落库不变式守卫:写入任何内容之前,run 目标 == 上下文 == 规格
            // 身份三者必须一致。
            if (!snapshot.projectId().equals(run.projectId())
                    || !snapshot.routeId().equals(route.id())
                    || !snapshot.tipNodeId().equals(route.tipNodeId())
                    || !snapshot.routeId().equals(run.routeId())
                    || !snapshot.tipNodeId().equals(run.inputNodeId())) {
                throw new ModelContractException(
                        "Artifact persistence invariant violated: run/context/spec targets diverged");
            }
            // 所有权 fencing(原子协议):规格快照落库与带所有权条件的检查点
            // 写入(markPersistedSpecSnapshot)在同一事务——新执行器接管后,
            // 旧执行器的条件检查点落空(0 行),整个事务回滚,快照不落库。
            final String gateTrace = trace;
            SpecSnapshot persisted = transactionTemplate.execute(tx -> {
                // 所有权协议(第四轮):事务第一条语句取所有权行 FOR SHARE
                // 并验证代次,锁保持到提交——接管与本事务互斥(R4-A)。
                executionFence.lockOwnershipForWrite();
                SpecSnapshot created = specSnapshotService.createSnapshot(
                        run.projectId(), route.id(), route.tipNodeId(), snapshot.id(),
                        "markdown", sections, unresolvedItems, sourceRefs, run.id());
                agentRunService.markPersistedSpecSnapshot(run.id(), created.id(), gateTrace);
                return created;
            });
            trace = appendTrace(trace, "persisted_spec_snapshot");
            progressRecorder.note(run.id(), AgentRunPhase.ARTIFACT_GENERATING,
                    "规格文档已生成，共 " + sections.size() + " 个章节");
            trace = appendTrace(trace, "completed");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
            eventService.append(run.id(), AgentRunPhase.COMPLETED, "RUN_COMPLETED",
                    Map.of("producedSpecSnapshotId", persisted.id().toString()));

            return new SpecGenerationOutcome(run.id(), persisted.id());
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /** runtime 侧持有来源引用解析;模型绝不能自造 id。 */
    private List<SourceReference> distinctSourceRefs(
            AgentArtifactResponse.ArtifactGenerationResult result) {
        Set<String> refs = new LinkedHashSet<>();
        for (AgentArtifactResponse.ArtifactSection section : result.sections()) {
            refs.addAll(section.sourceRefs());
        }
        List<SourceReference> parsed = new ArrayList<>();
        for (String ref : refs) {
            int separator = ref.indexOf(':');
            if (separator <= 0 || separator == ref.length() - 1) {
                throw new ModelContractException(
                        "Spec source reference must be kind:uuid: " + ref);
            }
            parsed.add(SourceReference.of(
                    SourceKind.fromCode(ref.substring(0, separator)),
                    UUID.fromString(ref.substring(separator + 1))));
        }
        return parsed;
    }

    private SpecDraft toSpecDraft(AgentArtifactResponse.ArtifactGenerationResult result) {
        Map<String, String> sections = new LinkedHashMap<>();
        Map<String, List<String>> refsBySection = new LinkedHashMap<>();
        for (AgentArtifactResponse.ArtifactSection section : result.sections()) {
            sections.put(section.title(), section.content());
            refsBySection.put(section.title(),
                    section.sourceRefs() == null ? List.of() : section.sourceRefs());
        }
        return new SpecDraft(sections, result.unresolvedItems(), refsBySection);
    }

    /**
     * 判断本 run 是否是针对显式路线排队的(由 {@code RunService} 记录在
     * RUN_CREATED 事件载荷里)。只有显式路线 run 才允许跳过 Active 相等规则;
     * Active 模式 run 保持 fail-closed。
     */
    private boolean isExplicitRouteRun(AgentRun run) {
        return eventService.findByRunId(run.id()).stream()
                .filter(event -> "RUN_CREATED".equals(event.eventType()))
                .map(com.specagent.agent.runevent.AgentRunEvent::payload)
                .findFirst()
                .map(payload -> "EXPLICIT".equals(payload.get("routeSelection")))
                .orElse(false);
    }

    /**
     * 拒绝从"丢失了用户已给出回答"的状态派生规格。
     *
     * 判定器是共享的 {@link AnswerProcessingGate},作用于路线的有效回答
     * 历史——路线本地回答加上冻结的继承前缀,正是 {@code ContextBuilder}
     * 折入规格上下文的集合。此前只查路线本地 tip 的实现假设继承回答总是
     * 已处理;该假设不成立,因此从"STATE_UPDATE 从未完成"的已回答节点分叉
     * 时,这个门会放行,规格静默遗漏继承回答。命令入口在入队前会拒绝同样的
     * 状态;本门兜底覆盖"先排队、后失效"的 run。
     */
    private void failIfTipAnswerUnprocessed(Route route) {
        answerProcessingGate.firstUnprocessedAnswer(route.id(), route.tipNodeId())
                .ifPresent(pending -> {
                    throw new IncompleteAnswerCycleException(
                            "Answer " + pending.id() + " (saved on route "
                                    + pending.routeId() + ") has no processed state"
                                    + " update; retry that answer on its route before"
                                    + " generating an incomplete spec",
                            pending.id(), pending.routeId(), pending.nodeId());
                });
    }

    private void failIfNotTerminal(UUID runId, String trace, RuntimeException ex) {
        LOG.warn("Agent run {} failed at {}: {}", runId, trace, ex.getMessage());
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            String reason = RunFailureReasons.reasonCode(ex);
            agentRunFailureService.fail(runId, appendTrace(trace, "failed:" + reason), ex);
        }
    }

    private String appendTrace(String trace, String step) {
        return trace + ">" + step;
    }

    /** 一次规格生成循环结束后的事后视图。 */
    public record SpecGenerationOutcome(UUID runId, UUID producedSpecSnapshotId) {
    }
}
