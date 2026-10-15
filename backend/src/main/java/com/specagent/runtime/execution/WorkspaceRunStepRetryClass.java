package com.specagent.runtime.execution;

/**
 * 文件名:WorkspaceRunStepRetryClass.java
 *
 * 用途:步骤的重试类别(design §5.5 的 {@code retry_class})。它决定恢复时
 * 这一步是否允许重放。
 */
public enum WorkspaceRunStepRetryClass {

    /**
     * 可安全重试:重放不会产生重复的业务副作用(幂等约束或纯计算)。
     * 例如已经幂等的 checkpoint 写入。
     */
    SAFE,

    /**
     * 不可安全重试:重放可能重复外部副作用。结果不确定时一律停止并留下
     * 核对记录,绝不自动重放(design §7)。
     */
    UNSAFE;

    public String code() {
        return name();
    }

    public static WorkspaceRunStepRetryClass fromCode(String code) {
        if (code == null) {
            return SAFE;
        }
        return WorkspaceRunStepRetryClass.valueOf(code.toUpperCase());
    }
}
