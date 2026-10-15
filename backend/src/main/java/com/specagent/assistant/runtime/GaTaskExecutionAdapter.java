package com.specagent.assistant.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.access.ActorContextHolder;
import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.ExecutionToken;
import com.specagent.runtime.execution.ExecutionTokenContext;
import com.specagent.runtime.execution.RuntimeTaskInputStore;
import com.specagent.runtime.execution.TaskActor;
import com.specagent.runtime.execution.TaskExecutionAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:GaTaskExecutionAdapter.java
 *
 * 用途:把公共调度层认领到的全局助手任务交给既有的 GA 协调器执行
 * (design §10)。
 *
 * <p><b>输入来自数据库,不来自内存参数。</b>执行输入(message 与 UI context)在
 * 入队事务里已经落库;这里读回来重建 {@link GaHostContext.UiRequest}。因此进程崩溃
 * 后接管者仍能重建同一次执行,而不是丢失输入。
 *
 * <p>执行代次通过 {@link ExecutionTokenContext} 绑定,{@code GaExecutionPreparation}
 * 读它写入 {@code ga_executions.execution_epoch} 与执行信封——硬编码的 epoch=1
 * 由此消除。
 */
@Component
public class GaTaskExecutionAdapter implements TaskExecutionAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(GaTaskExecutionAdapter.class);

    private static final Set<String> TASK_TYPES = Set.of(GaTaskTypes.EXECUTION);

    private final GaExecutionCoordinator coordinator;
    private final RuntimeTaskInputStore inputs;
    private final com.specagent.assistant.conversation.GlobalAssistantRunRepository runs;
    private final ObjectMapper mapper = new ObjectMapper();

    public GaTaskExecutionAdapter(GaExecutionCoordinator coordinator,
                                  RuntimeTaskInputStore inputs,
                                  com.specagent.assistant.conversation.GlobalAssistantRunRepository runs) {
        this.coordinator = coordinator;
        this.inputs = inputs;
        this.runs = runs;
    }

    @Override
    public Set<String> taskTypes() {
        return TASK_TYPES;
    }

    @Override
    public void execute(ExecutionClaim claim) {
        UUID runId = claim.runId();
        ExecutionToken token = claim.token();
        Map<String, Object> payload = readPayload(claim.taskId());

        Object message = payload.get("message");
        if (message == null || String.valueOf(message).isBlank()) {
            throw new IllegalStateException(
                    "GA task " + claim.taskId() + " has no persisted execution input; "
                            + "refusing to execute without the original message");
        }
        GaHostContext.UiRequest uiRequest = uiRequestOf(payload);
        UUID threadId = threadIdOf(runId);

        // 执行主体从**持久化任务行**重建(P2-D):GA 执行期对会话正文的写入
        // 会经过 requireThreadOwnership 私有性收口点。Recovery Worker 没有请求
        // 线程,若不在此绑定主体,OIDC 模式下每次执行都会被"未认证"拒绝
        // (失败关闭,但 GA 在多人模式下不可用)。绑定只覆盖本次执行,结束后复原。
        TaskActor actor = requirePersistedActor(claim);
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(actor.toActorContext())) {
            ExecutionTokenContext.runWith(token, () ->
                    coordinator.executeRun(threadId, runId, String.valueOf(message), uiRequest));
        }
    }

    /**
     * 任务行的持久化主体。缺失即无法重建可信归属——失败关闭,绝不以
     * "没有主体"执行(那等于给后台任务开了一个隐形超级用户)。
     */
    private static TaskActor requirePersistedActor(ExecutionClaim claim) {
        TaskActor actor = claim.task() == null ? null : claim.task().actor();
        if (actor == null || actor.missingOrg()) {
            throw new IllegalStateException(
                    "GA runtime task " + claim.taskId()
                            + " has no persisted actor/org; refusing to execute without attribution");
        }
        return actor;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(UUID taskId) {
        String json = inputs.read(taskId).orElseThrow(() -> new IllegalStateException(
                "GA task " + taskId + " has no persisted execution input"));
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "GA task " + taskId + " has an unreadable execution input", ex);
        }
    }

    private GaHostContext.UiRequest uiRequestOf(Map<String, Object> payload) {
        Object page = payload.get("currentPage");
        Object entityType = payload.get("selectedEntityType");
        Object entityId = payload.get("selectedEntityId");
        if (page == null && entityType == null) {
            return null;
        }
        GaHostContext.UiRequest.SelectedRef selected = entityType == null || entityId == null
                ? null
                : new GaHostContext.UiRequest.SelectedRef(
                        String.valueOf(entityType), String.valueOf(entityId));
        return new GaHostContext.UiRequest(
                page == null ? null : String.valueOf(page), selected);
    }

    private UUID threadIdOf(UUID runId) {
        // 会话归属只从数据库读取:客户端输入绝不能指定执行所属的会话。
        return runs.findById(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "GA run " + runId + " disappeared before execution"))
                .threadId();
    }
}
