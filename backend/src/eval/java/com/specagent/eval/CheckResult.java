package com.specagent.eval;

/**
 * 文件名:CheckResult.java
 *
 * 用途:单个确定性校验(不变量或必填属性)的结果:名称、是否通过和
 * 失败明细。
 *
 * 协作:由各分层校验器产出,汇总进 {@link Violation} 和评测报告。
 */
public record CheckResult(String name, boolean passed, String detail) {
}
