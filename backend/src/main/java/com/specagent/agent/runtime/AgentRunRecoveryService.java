package com.specagent.agent.runtime;

import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.common.ApiException;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:AgentRunRecoveryService.java
 *
 * 用途:任务级失败恢复的读侧与提交侧。失败恢复绑定完整任务身份
 * (failedRunId、operation、routeId——允许 null、sourceNodeId),全部事实
 * 取自可信持久化:失败的 run 行 + 它的 RUN_CREATED/RUN_FAILED 事件。
 * 客户端只提交"重试哪个失败任务",绝不提交可重放的 payload。
 *
 * 读侧(listUnresolved):项目内全部 FAILED run,按任务身份做收敛——
 * - 已被同身份的后续成功取代、或已被更新的同身份失败接替的条目不再展示
 *   (历史仍在 agent_runs 中保留);
 * - 同身份存在在途 run(用户的重新提交或本服务的重试)时,条目携带
 *   retryRunId,UI 禁用重复重试并显示进度;
 * - 路线/节点已变化导致目标过期时标记 stale,不再提供重试。
 *
 * 提交侧(retry):服务端从持久化记录还原原始意图并派发到既有领域命令
 * (起草/回答/续答/规格/换题/节点查询),目标路线永远显式取自失败任务,
 * 绝不回退 Active/first/latest。重试身份用确定性幂等键
 * {@code retry:<failedRunId>}:双击/跨标签页并发最多产生一个有效尝试,
 * 已在途时返回同一个 run。Answer 已持久化的失败映射到 RESUME_ANSWER
 * (复用现有修复路径),绝不重复创建 Answer 或重复应用补丁。
 *
 * 换题重试永远是 REGENERATE_NODE;规格重试永远是 GENERATE_SPEC;
 * 续跑(CONTINUE)失败的重试是原路线上的起草——不会串成别的操作。
 */
@Service
public class AgentRunRecoveryService {

    static final String RETRY_KEY_PREFIX = "retry:";

    private final AgentRunRepository agentRunRepository;
    private final AgentRunEventService eventService;
    private final RunService runService;
    private final RouteRepository routeRepository;
    private final NodeRepository nodeRepository;

    public AgentRunRecoveryService(AgentRunRepository agentRunRepository,
                                   AgentRunEventService eventService,
                                   RunService runService,
                                   RouteRepository routeRepository,
                                   NodeRepository nodeRepository) {
        this.agentRunRepository = agentRunRepository;
        this.eventService = eventService;
        this.runService = runService;
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
    }

    /** 项目内未解决的失败,最新在前。已被成功/更新失败取代的不展示。 */
    public List<UnresolvedFailureView> listUnresolved(UUID projectId) {
        List<AgentRun> runs = agentRunRepository.findByProject(projectId);
        List<AgentRun> failed = runs.stream()
                .filter(run -> run.status() == AgentRunStatus.FAILED)
                .sorted(Comparator.comparing(AgentRun::createdAt).reversed())
                .toList();
        return failed.stream()
                .map(failure -> describe(projectId, runs, failure))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<UnresolvedFailureView> describe(UUID projectId, List<AgentRun> runs,
                                                     AgentRun failure) {
        // 1) 精确重试链:幂等键 retry:<failedRunId> 的最新后代
        Optional<AgentRun> descendant = runs.stream()
                .filter(run -> run.idempotencyKey() != null
                        && run.idempotencyKey().startsWith(RETRY_KEY_PREFIX + failure.id()))
                .max(Comparator.comparing(AgentRun::createdAt));
        if (descendant.isPresent()) {
            AgentRun retry = descendant.get();
            if (retry.status() == AgentRunStatus.COMPLETED) {
                return Optional.empty(); // 重试已成功:失败已被解决
            }
            if (retry.status() == AgentRunStatus.FAILED) {
                return Optional.empty(); // 更新的失败接替;它自己会作为条目出现
            }
            return Optional.of(view(projectId, failure, retry)); // 在途:显示进度
        }
        // 2) 同任务身份的后续 run(用户手动重发等):在途 → 显示进度;
        //    成功 → 已解决;失败 → 由它代表。
        Optional<AgentRun> newer = runs.stream()
                .filter(run -> run.createdAt().isAfter(failure.createdAt()))
                .filter(run -> sameRecoveryTask(run, failure))
                .max(Comparator.comparing(AgentRun::createdAt));
        if (newer.isPresent()) {
            AgentRun retry = newer.get();
            if (retry.status() == AgentRunStatus.COMPLETED) {
                return Optional.empty();
            }
            if (retry.status() == AgentRunStatus.FAILED) {
                return Optional.empty();
            }
            return Optional.of(view(projectId, failure, retry));
        }
        return Optional.of(view(projectId, failure, null));
    }

    private UnresolvedFailureView view(UUID projectId, AgentRun failure, AgentRun retryRun) {
        String reasonCode = latestFailureReason(failure.id());
        Staleness staleness = stalenessOf(failure);
        String action = staleness.stale() ? "STALE" : actionFor(failure, answerPersisted(failure), reasonCode);
        return UnresolvedFailureView.of(projectId, failure, reasonCode,
                RunFailureReasons.userCopyFor(reasonCode), action,
                actionLabelFor(action, failure), staleness.stale(), retryRun);
    }

    private String latestFailureReason(UUID runId) {
        return eventService.findByRunId(runId).stream()
                .filter(event -> "RUN_FAILED".equals(event.eventType()))
                .reduce((first, second) -> second)
                .map(event -> String.valueOf(event.payload().getOrDefault("reason", "UNKNOWN")))
                .orElse("UNKNOWN");
    }

    /** Answer 已落库:produced_answer_id 或 RUN_CREATED payload 的 answerId。 */
    private boolean answerPersisted(AgentRun failure) {
        if (failure.producedAnswerId() != null) {
            return true;
        }
        return eventService.findByRunId(failure.id()).stream()
                .filter(event -> "RUN_CREATED".equals(event.eventType()))
                .findFirst()
                .map(event -> event.payload().get("answerId") != null)
                .orElse(false);
    }

    private String actionFor(AgentRun failure, boolean answerSaved, String reasonCode) {
        if (isModelConfigFailure(reasonCode)) {
            return "GO_TO_MODEL_SETTINGS";
        }
        if (answerSaved) {
            return "CONTINUE_PROCESSING";
        }
        return switch (failure.triggerType()) {
            case ANSWER_CYCLE -> "RETRY_GENERATION";
            case DECISION_CYCLE, CONTINUE_CYCLE -> "RETRY_GENERATION";
            case GENERATE_SPEC -> "RETRY_SPEC";
            case REGENERATE_NODE -> "RETRY_REGENERATE";
            case NODE_QUERY -> "RETRY_NODE_QUERY";
            default -> "STALE";
        };
    }

    /**
     * 确定的凭据配置类失败:入口是模型设置。
     *
     * brain_unavailable / model_provider_failure 刻意不在此列(第二轮复核
     * R2-D):这两个码同样覆盖连接拒绝、5xx、服务不可用与限流——临时故障
     * 在服务恢复后旧失败事件不会变化,若把它们导向设置页,用户会在
     * "修改凭据"的循环里卡死。无法从失败码确定是配置错误时不武断要求改
     * 凭据:这些失败按各自的操作家族给出重试动作,设置是否真的有问题由
     * 重试的真实结果说话。
     */
    static boolean isModelConfigFailure(String reasonCode) {
        return "NotConfiguredException".equals(reasonCode)
                || "model_not_configured".equals(reasonCode);
    }

    private String actionLabelFor(String action, AgentRun failure) {
        return switch (action) {
            case "CONTINUE_PROCESSING" -> "继续处理";
            case "RETRY_GENERATION" -> "重试生成";
            case "RETRY_REGENERATE" -> "重试换题";
            case "RETRY_SPEC" -> "重试生成规格";
            case "RETRY_NODE_QUERY" -> "重试该查询";
            case "GO_TO_MODEL_SETTINGS" -> "前往模型设置";
            case "STALE" -> "查看变化";
            default -> "查看";
        };
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 重试一个失败任务:服务端校验资格、还原原始意图并派发既有领域命令。
     * 返回新(或已存在的幂等重放)run;调用方以 202 暴露。
     */
    public AgentRun retry(UUID projectId, UUID failedRunId) {
        AgentRun failure = agentRunRepository.findById(failedRunId)
                .orElseThrow(() -> ApiException.notFound("RUN_NOT_FOUND", "Run not found"));
        if (!failure.projectId().equals(projectId)) {
            throw ApiException.notFound("RUN_NOT_FOUND", "Run not found");
        }
        if (failure.status() != AgentRunStatus.FAILED) {
            throw ApiException.conflict("NOT_A_FAILED_RUN",
                    "Only failed runs can be retried");
        }
        List<AgentRun> runs = agentRunRepository.findByProject(projectId);
        // 同任务身份的更新状态检查(含重试链)
        Optional<AgentRun> descendant = runs.stream()
                .filter(run -> run.idempotencyKey() != null
                        && run.idempotencyKey().startsWith(RETRY_KEY_PREFIX + failure.id()))
                .max(Comparator.comparing(AgentRun::createdAt));
        if (descendant.isPresent() && descendant.get().status() != AgentRunStatus.FAILED) {
            // 幂等接受:重试已在途(双击/跨标签页/超时后重试)→ 返回同一 run,
            // 绝不产生第二个有效尝试。
            if (descendant.get().status() == AgentRunStatus.COMPLETED) {
                throw conflictFor(descendant.get());
            }
            return descendant.get();
        }
        Optional<AgentRun> newer = runs.stream()
                .filter(run -> run.createdAt().isAfter(failure.createdAt()))
                .filter(run -> sameRecoveryTask(run, failure))
                .max(Comparator.comparing(AgentRun::createdAt));
        if (newer.isPresent()) {
            if (newer.get().status() == AgentRunStatus.FAILED
                    || newer.get().status() == AgentRunStatus.COMPLETED) {
                throw conflictFor(newer.get());
            }
            // 用户已经手动重新提交且仍在途:对账语义,返回同一 run
            return newer.get();
        }
        Staleness staleness = stalenessOf(failure);
        if (staleness.stale()) {
            throw ApiException.conflict("STALE_RECOVERY_TARGET",
                    "The failure target has changed: " + staleness.reason());
        }
        return dispatchRetry(failure);
    }

    private ApiException conflictFor(AgentRun newer) {
        return switch (newer.status()) {
            case COMPLETED -> ApiException.conflict("SUPERSEDED_BY_SUCCESS",
                    "A newer attempt with the same target already completed");
            case FAILED -> ApiException.conflict("SUPERSEDED_BY_NEWER_FAILURE",
                    "A newer attempt with the same target already failed: "
                            + newer.id());
            default -> ApiException.conflict("RECOVERY_IN_FLIGHT",
                    "A newer attempt with the same target is still running: "
                            + newer.id());
        };
    }

    /** 从持久化事实还原意图并派发;绝不信任客户端 payload。 */
    private AgentRun dispatchRetry(AgentRun failure) {
        UUID projectId = failure.projectId();
        Map<String, Object> payload = eventService.findByRunId(failure.id()).stream()
                .filter(event -> "RUN_CREATED".equals(event.eventType()))
                .map(event -> event.payload())
                .findFirst()
                .orElse(null);
        if (payload == null) {
            throw ApiException.conflict("RECOVERY_CONTEXT_UNAVAILABLE",
                    "This legacy failure has no persisted run intent; it cannot be "
                            + "retried safely. Re-issue the operation from its original place.");
        }
        String key = RETRY_KEY_PREFIX + failure.id();
        String fingerprint = failure.requestFingerprint();

        switch (failure.triggerType()) {
            case DECISION_CYCLE, CONTINUE_CYCLE -> {
                if (failure.routeId() == null) {
                    throw ApiException.conflict("RECOVERY_CONTEXT_UNAVAILABLE",
                            "Draft failure has no route to retry on");
                }
                return runService.createQueuedDraftQuestion(
                        projectId, key, fingerprintOrDefault(failure, payload), failure.routeId());
            }
            case ANSWER_CYCLE -> {
                UUID answerId = failure.producedAnswerId() != null
                        ? failure.producedAnswerId()
                        : uuidOrNull(payload.get("answerId"));
                String operation = answerId != null ? "RESUME_ANSWER"
                        : str(payload.getOrDefault("operation", failure.operation() == null
                                ? "ANSWER_TIP" : failure.operation()));
                return runService.createQueuedRunWithInputResultForRoute(
                        projectId,
                        operation,
                        failure.inputNodeId(),
                        uuidOrNull(payload.get("selectedOptionId")),
                        uuidList(payload.get("selectedOptionIds")),
                        str(payload.get("freeText")),
                        answerId,
                        key,
                        fingerprintOrDefault(failure, payload),
                        persistenceIntent(payload),
                        failure.routeId());
            }
            case GENERATE_SPEC -> {
                if (failure.routeId() == null) {
                    throw ApiException.conflict("RECOVERY_CONTEXT_UNAVAILABLE",
                            "Spec failure has no route to retry on");
                }
                return runService.createQueuedArtifactGeneration(
                        projectId, key, fingerprintOrDefault(failure, payload), failure.routeId());
            }
            case REGENERATE_NODE -> {
                UUID sourceRouteId = uuidOrNull(payload.get("routeId"));
                UUID targetNodeId = uuidOrNull(payload.get("nodeId"));
                if (sourceRouteId == null) sourceRouteId = failure.routeId();
                if (targetNodeId == null) targetNodeId = failure.inputNodeId();
                if (sourceRouteId == null || targetNodeId == null) {
                    throw ApiException.conflict("RECOVERY_CONTEXT_UNAVAILABLE",
                            "Regenerate failure is missing its persisted target");
                }
                return runService.createQueuedRegenerate(
                        projectId, sourceRouteId, targetNodeId,
                        str(payload.get("freeText")), key,
                        fingerprintOrDefault(failure, payload));
            }
            case NODE_QUERY -> {
                UUID nodeId = uuidOrNull(payload.get("nodeId"));
                if (nodeId == null) nodeId = failure.inputNodeId();
                String question = str(payload.get("question"));
                if (nodeId == null || question == null || question.isBlank()) {
                    throw ApiException.conflict("RECOVERY_CONTEXT_UNAVAILABLE",
                            "Node query failure is missing its persisted question");
                }
                return runService.createQueuedNodeQuery(
                        projectId, uuidOrNull(payload.get("routeId")), nodeId, question,
                        key, fingerprintOrDefault(failure, payload));
            }
        }
        throw ApiException.conflict("RECOVERY_CONTEXT_UNAVAILABLE",
                "This failure type cannot be retried automatically");
    }

    private String fingerprintOrDefault(AgentRun failure, Map<String, Object> payload) {
        if (failure.requestFingerprint() != null && !failure.requestFingerprint().isBlank()) {
            return failure.requestFingerprint();
        }
        return AgentRunRequestFingerprint.forClientRequest(
                failure.projectId(),
                failure.operation() == null ? "" : failure.operation(),
                failure.inputNodeId(),
                failure.routeId(),
                null, null, null, null, null);
    }

    // -------------------------------------------------------------- 资格判定

    private record Staleness(boolean stale, String reason) {
    }

    /** 服务端校验失败目标是否仍然成立;过期目标绝不提供重试。 */
    private Staleness stalenessOf(AgentRun failure) {
        if (failure.routeId() != null) {
            Route route = routeRepository.findById(failure.routeId()).orElse(null);
            if (route == null || route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
                return new Staleness(true, "route is gone or no longer open");
            }
        }
        UUID nodeId = failure.inputNodeId();
        if (nodeId != null) {
            Node node = nodeRepository.findById(nodeId).orElse(null);
            if (node == null || node.isRetracted()) {
                return new Staleness(true, "source node is gone or retracted");
            }
        }
        // 草稿/续跑/规格类:锚点必须是路线的当前 tip——路线已前进时,
        // 重试会写到错误的位置,应让用户查看变化。
        if (failure.routeId() != null) {
            Route route = routeRepository.findById(failure.routeId()).orElse(null);
            if (route != null && failure.triggerType() != AgentRunTriggerType.ANSWER_CYCLE) {
                boolean tipMoved = failure.inputNodeId() != null
                        && !failure.inputNodeId().equals(route.tipNodeId());
                boolean specRegeneration = failure.triggerType() == AgentRunTriggerType.GENERATE_SPEC
                        || failure.triggerType() == AgentRunTriggerType.REGENERATE_NODE;
                if (tipMoved && !specRegeneration) {
                    return new Staleness(true, "route tip moved past the failed target");
                }
            }
        }
        return new Staleness(false, null);
    }

    /** 同一任务家族:回答/起草(含续跑)/规格/换题/节点查询。 */
    static boolean sameFamily(AgentRun a, AgentRun b) {
        return familyOf(a).equals(familyOf(b));
    }

    private static String familyOf(AgentRun run) {
        String operation = run.operation() == null ? "" : run.operation();
        return switch (run.triggerType()) {
            case ANSWER_CYCLE -> "answer";
            case DECISION_CYCLE, CONTINUE_CYCLE -> "draft";
            case GENERATE_SPEC -> "spec";
            case REGENERATE_NODE -> "regenerate";
            case NODE_QUERY -> "node_query";
            default -> "other";
        };
    }

    /** 同一目标:routeId 与 inputNodeId 均 null 安全相等。 */
    private static boolean sameTarget(AgentRun a, AgentRun b) {
        return java.util.Objects.equals(a.routeId(), b.routeId())
                && java.util.Objects.equals(a.inputNodeId(), b.inputNodeId());
    }

    /**
     * 同一恢复任务 = 同一家族 + 同一目标 + 同一可信意图。
     * NODE_QUERY 的位置相同不等于同一个任务:同节点、同路线的不同问题
     * 是独立任务——后一个不相关问题成功绝不能把前一个失败标成已解决
     * (那是用隐藏失败卡的方式掩盖数据冲突)。问题文本取自持久化的
     * RUN_CREATED 事件;两边的意图都无法还原时才退回位置判定。
     */
    private boolean sameRecoveryTask(AgentRun a, AgentRun b) {
        if (!sameFamily(a, b) || !sameTarget(a, b)) {
            return false;
        }
        if ("node_query".equals(familyOf(a))) {
            String questionA = runCreatedIntent(a, "question");
            String questionB = runCreatedIntent(b, "question");
            if (questionA != null && questionB != null) {
                return questionA.equals(questionB);
            }
        }
        return true;
    }

    /** RUN_CREATED 事件 payload 中的字符串意图字段;缺失时返回 null。 */
    private String runCreatedIntent(AgentRun run, String field) {
        return eventService.findByRunId(run.id()).stream()
                .filter(event -> "RUN_CREATED".equals(event.eventType()))
                .findFirst()
                .map(event -> str(event.payload().get(field)))
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
    }

    // ------------------------------------------------------------------ 工具

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static UUID uuidOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value);
        if (raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> uuidList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return list.stream()
                .map(AgentRunRecoveryService::uuidOrNull)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static AgentEvent.PersistenceIntent persistenceIntent(Map<String, Object> payload) {
        Object raw = payload.get("persistenceIntent");
        if (raw == null) {
            return null;
        }
        try {
            return AgentEvent.PersistenceIntent.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
