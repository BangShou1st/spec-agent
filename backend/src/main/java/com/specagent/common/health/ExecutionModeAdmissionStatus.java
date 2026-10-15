package com.specagent.common.health;

/**
 * 文件名:ExecutionModeAdmissionStatus.java
 *
 * 用途:调度模式准入(design §11)的只读健康视图。定义在 {@code common.health},
 * 使健康检查不依赖 runtime.execution 实现(健康层只依赖自己定义的端口);
 * 由 {@code com.specagent.runtime.execution.ExecutionModeAdmission} 实现。
 *
 * <p>准入对 API-only 实例同样重要:入队路径必须与 Worker 舰队的调度模式一致,
 * 准入丢失时纯 API 进程也必须对外表现为不健康。
 */
public interface ExecutionModeAdmissionStatus {

    /** 数据库登记的调度模式(诊断用);准入未激活时为 null。 */
    String admittedMode();

    /** 准入是否已永久丢失(fail-closed 闩锁)。 */
    boolean isLost();

    /** 丢失原因;未丢失时为 null。 */
    String getLostReason();
}
