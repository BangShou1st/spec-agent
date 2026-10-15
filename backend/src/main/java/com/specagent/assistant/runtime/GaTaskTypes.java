package com.specagent.assistant.runtime;

/**
 * 文件名:GaTaskTypes.java
 *
 * 用途:全局助手调度任务的稳定类型码(design §5.1)。
 *
 * <p>与画布的 trigger code 并列但独立:GA 只有一种执行任务。集中定义使
 * 适配器、恢复适配器与入队路径共享同一个字面量,不会各写一份而漂移。
 */
public final class GaTaskTypes {

    private GaTaskTypes() {
    }

    /** 一次全局助手执行(对应既有 GaExecutionCoordinator 的单次 launch)。 */
    public static final String EXECUTION = "ga_execution";
}
