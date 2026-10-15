package com.specagent.runtime.execution;

/**
 * 文件名:RuntimeTaskFamily.java
 *
 * 用途:公共调度层的任务族。一个任务恰好属于一个业务 Run,family 决定它引用
 * 哪张业务表(workspace_run_id 或 ga_run_id),并提供独立的容量与公平性边界
 * (见 docs/v2/PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §4、§5.1)。
 */
public enum RuntimeTaskFamily {

    /** 画布 AgentRun(agent_runs)。取得 project:{projectId} 独占执行范围。 */
    WORKSPACE("workspace_run_id"),

    /** 全局助手 Run(global_assistant_runs)。取得 ga-thread:{threadId} 独占范围。 */
    GLOBAL_ASSISTANT("ga_run_id");

    private final String runColumn;

    RuntimeTaskFamily(String runColumn) {
        this.runColumn = runColumn;
    }

    /** 归属业务 Run 的外键列名(白名单常量,绝不来自外部输入)。 */
    public String runColumn() {
        return runColumn;
    }

    public String code() {
        return name();
    }

    public static RuntimeTaskFamily fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Runtime task family must not be null");
        }
        return RuntimeTaskFamily.valueOf(code.toUpperCase());
    }
}
