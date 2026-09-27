package com.specagent.eval;

/**
 * 文件名:Violation.java
 *
 * 用途:一条带类型失败分类({@link FailureClass})和人类可读明细的评测
 * 违例记录。
 *
 * 协作:由各校验器产出,附着在 {@link AttemptResult} /
 * {@link ObservationEnvelope} 上,供 {@link EvalSummary} 汇总。
 */
public record Violation(FailureClass failureClass, String detail) {
}
