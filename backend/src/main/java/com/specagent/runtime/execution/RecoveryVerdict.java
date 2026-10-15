package com.specagent.runtime.execution;

/**
 * 文件名:RecoveryVerdict.java
 *
 * 用途:过期租约恢复的核验结论(design §7)。恢复检查只访问持久化数据,
 * 并保持短事务;结论只有"安全接管"与"结果不确定"两种可能。
 *
 * @param safe                  是否允许自动恢复(转 QUEUED 重新认领)
 * @param reason                稳定的可查询原因(写入 recovery_reason)
 * @param reconciliationRecord  人工核对记录(不安全时提供;绝不包含正文或凭证)
 */
public record RecoveryVerdict(boolean safe, String reason, String reconciliationRecord) {

    public RecoveryVerdict {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Recovery verdict requires a reason");
        }
    }

    /** 已核验持久化步骤,允许接管继续。 */
    public static RecoveryVerdict safe(String reason) {
        return new RecoveryVerdict(true, reason, null);
    }

    /** 结果不确定:默认不重放,诚实失败并留下核对记录。 */
    public static RecoveryVerdict unsafe(String reason, String reconciliationRecord) {
        return new RecoveryVerdict(false, reason, reconciliationRecord);
    }
}
