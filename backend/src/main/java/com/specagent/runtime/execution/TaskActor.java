package com.specagent.runtime.execution;

import com.specagent.access.ActorContext;
import com.specagent.access.ActorSource;

import java.util.UUID;

/**
 * 文件名:TaskActor.java
 *
 * 用途:runtime_tasks 行持久化的执行主体三元组(user、org、来源)。
 *
 * <p>它是"后台任务没有请求线程"这一事实的落点:Worker、恢复、内部 RPC 的
 * 主体必须从持久化任务记录<b>重建</b>,绝不继承发起线程,也绝不由 Brain
 * 声称(设计 §7"恢复 Worker 根据数据库重建,不依赖 HTTP 线程上下文")。
 *
 * <p>本类型住在 {@code runtime.execution},而非中性 {@code access} 模块:
 * 它由调度事实(runtime_tasks)派生,依赖方向恒为 runtime -> access;
 * 若放进 access,access 就要反向认识调度表,中立性门禁会失败。
 *
 * <p>它只做"事实到契约"的翻译,不做任何授权判定——判定永远在
 * {@link com.specagent.access.AuthorizationGuard} 内、以事务内的持久化
 * 事实 + 授权范围锁为准。
 */
public record TaskActor(UUID userId, UUID orgId, ActorSource source) {

    public TaskActor {
        if (source == null) {
            throw new IllegalArgumentException("TaskActor.source is required");
        }
    }

    /** 由当前(HTTP/命令)主体的显式快照生成待持久化的任务主体。 */
    public static TaskActor of(ActorContext actor) {
        if (actor == null) {
            throw new IllegalArgumentException(
                    "Cannot persist a task actor from a missing ActorContext");
        }
        return new TaskActor(actor.userId(), actor.orgId(), actor.source());
    }

    /**
     * 重建执行期主体。用户主体复用其来源(LOCAL/OIDC)；系统主体按其窄服务
     * 权限重建(无 user 字段)——绝不因为"没有 user"而提升为超级管理员。
     */
    public ActorContext toActorContext() {
        return switch (source) {
            case LOCAL -> ActorContext.local(userId, orgId);
            case OIDC -> ActorContext.oidc(userId, orgId);
            case SYSTEM -> ActorContext.system(orgId);
            case SERVICE -> ActorContext.service();
        };
    }

    public boolean isUser() {
        return source.isUser();
    }

    /** 组织缺失即无法重建可信归属,执行必须失败关闭。 */
    public boolean missingOrg() {
        return orgId == null;
    }
}