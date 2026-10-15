package com.specagent.runtime.execution;

/**
 * 文件名:RuntimeExecutionProtocol.java
 *
 * 用途:执行协议版本与范围键约定(docs/v2/
 * PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §5.1、§6.2)。
 *
 * <p>协议版本用于拒绝不兼容的 Worker 认领:认领查询带版本条件,升级期间
 * 旧进程无法抢走新协议的任务。范围键只由 Java 根据命令生成,模型与客户端
 * 输入绝不能指定范围。
 */
public final class RuntimeExecutionProtocol {

    private RuntimeExecutionProtocol() {
    }

    /** 当前执行协议版本;不兼容的 Worker 认领失败而不是降级执行。 */
    public static final int VERSION = 1;

    /**
     * 数据库范围的执行器准入 advisory lock 键(任选常量,全库唯一语义)。
     *
     * <p>同一把键、两种模式:单执行器实例({@code ExecutorLease})持有
     * <b>排他</b>会话锁;任务级实例({@code ExecutionModeAdmission},含
     * API-only)持有<b>共享</b>会话锁。共享与排他互斥、共享之间兼容,
     * 因此这把键同时承担"单执行器互斥"与"新旧调度器禁止混跑"两个职责
     * (design §11)。常量定义在本协议类中,保持 execution 包不反向依赖
     * agent 包(ArchUnit 无环)。
     */
    public static final long EXECUTOR_LOCK_KEY = 736_251_904_113L;

    /** 画布任务的独占范围:同一项目第一阶段串行。 */
    public static String projectScope(java.util.UUID projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("Project scope requires a project id");
        }
        return "project:" + projectId;
    }

    /** 全局助手任务的独占范围:同一会话至多一个活跃 Run。 */
    public static String threadScope(java.util.UUID threadId) {
        if (threadId == null) {
            throw new IllegalArgumentException("Thread scope requires a thread id");
        }
        return "ga-thread:" + threadId;
    }

    /** 全局容量槽键。 */
    public static final String SLOT_GLOBAL = "GLOBAL";
    /** 画布族容量槽键。 */
    public static final String SLOT_WORKSPACE = "WORKSPACE";
    /** 全局助手族容量槽键。 */
    public static final String SLOT_GLOBAL_ASSISTANT = "GLOBAL_ASSISTANT";

    /** 任务族对应的族级容量槽键。 */
    public static String familySlot(RuntimeTaskFamily family) {
        return family == RuntimeTaskFamily.WORKSPACE ? SLOT_WORKSPACE : SLOT_GLOBAL_ASSISTANT;
    }
}
