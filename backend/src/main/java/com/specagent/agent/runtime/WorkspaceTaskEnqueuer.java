package com.specagent.agent.runtime;

import com.specagent.access.AccessDeniedException;
import com.specagent.access.ActorContext;
import com.specagent.access.AuthorizationGuard;
import com.specagent.common.Ids;
import com.specagent.runtime.execution.RuntimeExecutionProtocol;
import com.specagent.runtime.execution.RuntimeExecutionSettings;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.TaskActor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 文件名:WorkspaceTaskEnqueuer.java
 *
 * 用途:把画布 AgentRun 登记为公共调度任务(design §6.1)。必须在<b>现有命令
 * 事务内</b>完成:与业务 Run、RUN_CREATED 事件同一事务提交,因此事务提交后
 * 立即崩溃也不会丢任务。
 *
 * <p><b>入队容量检查</b>在创建 Run <b>之前</b>完成,并且有数据库级串行化
 * (对配额行取排他锁,design §9):达到上限时拒绝并返回明确的可重试错误,
 * 队列保持 QUEUED 而不是无界增长。
 *
 * <p><b>范围</b>只由 Java 根据持久化的项目归属生成
 * ({@code project:{projectId}}),模型与客户端输入绝不能指定范围。
 */
@Service
public class WorkspaceTaskEnqueuer {

    private final RuntimeTaskRepository tasks;
    private final RuntimeExecutionSettings settings;
    private final com.specagent.runtime.execution.ExecutionOwnershipMode ownershipMode;
    private final AuthorizationGuard guard;

    public WorkspaceTaskEnqueuer(RuntimeTaskRepository tasks,
                                 RuntimeExecutionSettings settings,
                                 com.specagent.runtime.execution.ExecutionOwnershipMode ownershipMode,
                                 AuthorizationGuard guard) {
        this.tasks = tasks;
        this.settings = settings;
        this.ownershipMode = ownershipMode;
        this.guard = guard;
    }

    /**
     * 本进程是否由公共调度层调度任务。
     *
     * <p><b>为什么必须是一个开关而不是"两条路都跑"。</b>单执行器模式(默认)下
     * 认领仍由 {@link RunWorker} 直接驱动 {@code agent_runs},公共调度层不参与;
     * 此时若还登记 runtime_tasks,任务永远无人消费,排队配额会凭虚假积压把正常
     * 入队拒之门外。因此登记与配额<b>只在任务级所有权模式启用时生效</b>——
     * 一个开关选择一套调度器,与 design §11"禁止新旧调度器混跑"一致。
     */
    private boolean schedulingActive() {
        return ownershipMode != null && ownershipMode.taskLevel();
    }

    /**
     * 入队容量闸门:必须在创建业务 Run 之前调用,并在同一事务内保持到任务
     * 插入完成。超限抛出 {@link QueueCapacityExceededException}(可重试)。
     * 非任务级模式为 no-op(保持历史行为)。
     */
    public void assertQueueCapacity(UUID projectId) {
        if (!schedulingActive()) {
            return;
        }
        tasks.lockQueueQuota(RuntimeTaskFamily.WORKSPACE);
        if (tasks.inFlightCountForFamily(RuntimeTaskFamily.WORKSPACE)
                >= settings.getMaxQueuedTasks()) {
            throw new QueueCapacityExceededException(
                    "workspace execution queue is full (limit "
                            + settings.getMaxQueuedTasks() + ")");
        }
        String scope = RuntimeExecutionProtocol.projectScope(projectId);
        if (tasks.queuedCountForScope(scope) >= settings.getMaxQueuedPerScope()) {
            throw new QueueCapacityExceededException(
                    "project " + projectId + " already has " + settings.getMaxQueuedPerScope()
                            + " queued agent runs; wait for them before queueing more");
        }
    }

    /**
     * 在调用方事务内为新入队的画布 Run 建任务。
     *
     * <p>幂等:同一 Run 只落一行(唯一索引兜底),重复调用不产生第二个任务;
     * 已被幂等重放命中的既有 Run 也不会重复建任务。非任务级模式为 no-op。
     *
     * @param runId       agent_runs.id
     * @param projectId   项目 id(范围的唯一来源)
     * @param taskType    对应 trigger 的稳定类型码
     */
    public void enqueue(UUID runId, UUID projectId, String taskType) {
        if (!schedulingActive()) {
            return;
        }
        tasks.insertIfAbsent(Ids.random(), RuntimeTaskFamily.WORKSPACE, runId, null,
                taskType, settings.getMaxAttempts(), RuntimeExecutionProtocol.VERSION,
                taskActorFor(projectId));
        UUID taskId = tasks.findByWorkspaceRunId(runId).orElseThrow(
                () -> new IllegalStateException(
                        "runtime task missing after enqueue for run " + runId)).id();
        // 范围声明:同一项目第一阶段串行。重复声明幂等(主键冲突即忽略)。
        tasks.declareScope(taskId, RuntimeExecutionProtocol.projectScope(projectId));
    }

    /**
     * 解析并固化任务的执行主体(user/org/来源)。
     *
     * <p>归属组织取自<b>项目</b>(父关系推导),而不是主体自己声称的 org;
     * 两者必须一致,否则失败关闭。LOCAL 单机下未绑定主体回落到显式本地主体
     * ({@link AuthorizationGuard#ownershipActor()}),它在本地组织内,与项目
     * 组织一致;OIDC 下未绑定主体会直接拒绝。绝不把"没有身份"写成无主任务。
     */
    private TaskActor taskActorFor(UUID projectId) {
        ActorContext actor = guard.ownershipActor();
        UUID projectOrg = guard.resources().projectOrg(projectId).orElse(null);
        if (projectOrg == null || actor.orgId() == null || !projectOrg.equals(actor.orgId())) {
            // 归属不一致(跨组织)与项目不存在在这里同一种结果:不泄露存在性。
            throw AccessDeniedException.notVisible("Project not found");
        }
        return new TaskActor(actor.userId(), projectOrg, actor.source());
    }

    /** 入队容量超限:可重试,绝不是业务失败。 */
    public static class QueueCapacityExceededException extends IllegalStateException {
        public QueueCapacityExceededException(String message) {
            super(message);
        }
    }
}
