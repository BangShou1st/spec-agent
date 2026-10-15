package com.specagent.assistant.runtime;

import com.specagent.access.AccessDeniedException;
import com.specagent.access.AccessResourceRepository;
import com.specagent.access.ActorContext;
import com.specagent.access.ActorSource;
import com.specagent.access.AuthorizationGuard;
import com.specagent.access.LocalPrincipalIds;
import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.specagent.runtime.execution.ExecutionOwnershipMode;
import com.specagent.runtime.execution.RuntimeExecutionProtocol;
import com.specagent.runtime.execution.RuntimeExecutionSettings;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskInputStore;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.TaskActor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:GaTaskEnqueuer.java
 *
 * 用途:把全局助手 Run 登记为公共调度任务,并持久化它的执行输入
 * (design §6.1、§7)。
 *
 * <p><b>为什么需要一个独立的事务入口。</b>入队必须与"创建 Run + 写当轮用户消息"
 * 在<b>同一个事务</b>里提交,否则事务提交后立即崩溃就会留下"有 Run 却没有任务"
 * 的孤儿(无人会执行它)。把这三步放进本类的 {@link #createRunWithTask} 里,
 * 因为它是一个独立 bean,@Transactional 才真正生效(同类自调用不会走代理)。
 *
 * <p><b>持久化 dispatch 参数。</b>message 与 UI context 原本只在内存的方法参数里
 * 传递;进程崩溃后任务尚在而输入已失,接管者无法重建同一次执行。因此在入队
 * 事务内把输入与 UI context 一起落库,然后再对外应答。
 *
 * <p><b>范围</b>只由 Java 从 Run 行推导({@code ga-thread:{threadId}}),模型与客户端
 * 输入绝不能指定范围——同一助手会话保留"至多一个活跃 Run 与 steer 交接语义"。
 *
 * <p>未启用任务级调度时全部为 no-op:单实例行为与历史逐字一致。
 */
@Service
public class GaTaskEnqueuer {

    private final RuntimeTaskRepository tasks;
    private final RuntimeTaskInputStore inputs;
    private final RuntimeExecutionSettings settings;
    private final Json json;
    private final ExecutionOwnershipMode ownershipMode;
    private final com.specagent.assistant.conversation.GlobalAssistantConversationService conversations;
    private final AuthorizationGuard guard;

    public GaTaskEnqueuer(RuntimeTaskRepository tasks,
                          RuntimeTaskInputStore inputs,
                          RuntimeExecutionSettings settings,
                          Json json,
                          ExecutionOwnershipMode ownershipMode,
                          com.specagent.assistant.conversation.GlobalAssistantConversationService conversations,
                          AuthorizationGuard guard) {
        this.tasks = tasks;
        this.inputs = inputs;
        this.settings = settings;
        this.json = json;
        this.ownershipMode = ownershipMode;
        this.conversations = conversations;
        this.guard = guard;
    }

    /** 本进程是否由公共调度层调度 GA 任务。 */
    public boolean schedulingActive() {
        return ownershipMode != null && ownershipMode.taskLevel();
    }

    /**
     * 原子创建 GA Run + 当轮用户消息 + 调度任务 + 持久化执行输入。
     *
     * <p>四者同一事务:不会留下"有 Run 却没有任务"或"有任务却没有输入"的状态。
     * 调用方必须在本事务提交后才能唤醒执行(或交由公共调度层认领)。
     */
    @org.springframework.transaction.annotation.Transactional
    public com.specagent.assistant.conversation.GlobalAssistantRun createRunWithTask(
            UUID threadId, String message, String promptVersion,
            String contextProjectionVersion, String toolCatalogFingerprint,
            GaHostContext.UiRequest uiRequest) {
        var run = conversations.createRunWithUserMessage(threadId, message, promptVersion,
                contextProjectionVersion, toolCatalogFingerprint);
        enqueue(run.id(), threadId, message, uiRequest);
        return run;
    }

    /**
     * 在调用方事务内为 GA Run 建任务并持久化输入。
     *
     * @param runId     global_assistant_runs.id
     * @param threadId  会话 id(范围的唯一来源)
     * @param message   本轮用户消息(与已持久化的 USER 消息一致)
     * @param uiRequest UI context;可为 null
     */
    public void enqueue(UUID runId, UUID threadId, String message,
                        GaHostContext.UiRequest uiRequest) {
        if (!schedulingActive()) {
            return;
        }
        tasks.insertIfAbsent(Ids.random(), RuntimeTaskFamily.GLOBAL_ASSISTANT, null, runId,
                GaTaskTypes.EXECUTION, settings.getMaxAttempts(),
                RuntimeExecutionProtocol.VERSION, taskActorFor(threadId));
        UUID taskId = tasks.findByGaRunId(runId).orElseThrow(
                () -> new IllegalStateException(
                        "GA runtime task missing after enqueue for run " + runId)).id();
        tasks.declareScope(taskId, RuntimeExecutionProtocol.threadScope(threadId));

        // 执行输入:只保存重建执行所需的最小事实,绝不保存凭证。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        if (uiRequest != null) {
            payload.put("currentPage", uiRequest.currentPage());
            if (uiRequest.selectedEntity() != null) {
                payload.put("selectedEntityType", uiRequest.selectedEntity().type());
                payload.put("selectedEntityId", uiRequest.selectedEntity().id());
            }
        }
        inputs.write(taskId, json.write(payload));
    }

    /** 该 Run 已经登记过的任务 id(诊断与接管使用)。 */
    public java.util.Optional<UUID> taskIdOf(UUID runId) {
        return tasks.findByGaRunId(runId).map(task -> task.id());
    }

    /**
     * 解析并固化 GA 任务的执行主体(user/org/来源)。
     *
     * <p>归属组织与会话属主取自<b>持久化会话事实</b>(父关系推导),绝不接受
     * 客户端或模型传入。两条路径都以"会话属主 = 该会话每一轮 Run 的发起者"
     * 为准:
     * <ul>
     *   <li><b>命令路径</b>(浏览器建 Run):线程已绑定已验证主体,它必须与
     *       会话属主一致,否则失败关闭(不泄露会话存在性)。</li>
     *   <li><b>后台路径</b>(轮次续转 / 恢复):Worker 线程没有请求主体。
     *       此时按用户要求<b>沿用对应发起者</b>——即会话属主,而不是当前
     *       Worker 或管理员;来源由属主身份确定(本地主体→LOCAL,其余→OIDC)。</li>
     * </ul>
     */
    private TaskActor taskActorFor(UUID threadId) {
        AccessResourceRepository.ThreadFacts facts =
                guard.resources().threadFacts(threadId).orElse(null);
        if (facts == null || facts.orgId() == null || facts.ownerUserId() == null) {
            throw AccessDeniedException.notVisible("Conversation not found");
        }
        ActorContext bound = guard.currentOrNull();
        if (bound != null && bound.isUser()) {
            if (!facts.orgId().equals(bound.orgId())
                    || !facts.ownerUserId().equals(bound.userId())) {
                throw AccessDeniedException.notVisible("Conversation not found");
            }
            return new TaskActor(bound.userId(), facts.orgId(), bound.source());
        }
        return new TaskActor(facts.ownerUserId(), facts.orgId(),
                sourceOfOwner(facts.ownerUserId()));
    }

    /** 属主主体的来源:唯一的本地用户固定标识决定 LOCAL,其余为 OIDC。 */
    private static ActorSource sourceOfOwner(UUID ownerUserId) {
        return LocalPrincipalIds.USER_ID.equals(ownerUserId)
                ? ActorSource.LOCAL
                : ActorSource.OIDC;
    }
}
