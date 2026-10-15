package com.specagent.runtime.execution;

/**
 * 文件名:WorkspaceRunStepState.java
 *
 * 用途:画布步骤状态(design §5.5)。{@code SUCCEEDED} 必须有可验证的结果引用
 * ——调度器没有第二份业务结果事实源。
 */
public enum WorkspaceRunStepState {

    /** 已准备,尚未发起外部调用。 */
    PREPARED,
    /** 外部调用已发起,结果尚不可知。 */
    IN_FLIGHT,
    /** 已完成,且结果引用可验证。 */
    SUCCEEDED,
    /**
     * 结果不确定:外部调用可能已经发生,但结果没有持久化。
     * 恢复时默认<b>不重放</b>,停止并提供核对记录。
     */
    UNKNOWN,
    /** 失败(可安全重试的失败)。 */
    FAILED;

    public String code() {
        return name();
    }

    public static WorkspaceRunStepState fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Workspace run step state must not be null");
        }
        return WorkspaceRunStepState.valueOf(code.toUpperCase());
    }
}
