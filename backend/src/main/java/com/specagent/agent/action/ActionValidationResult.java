package com.specagent.agent.action;

import java.util.List;

/**
 * 文件名:ActionValidationResult.java
 *
 * 用途:动作提案对照当前图状态的校验结果。策略引擎与执行器据此
 * 决定是自动执行、要求人工确认,还是拒绝。
 *
 * 协作:valid()/rejected(...) 工厂方法统一构造结果;
 * 拒绝时 riskLevel 固定为 HIGH 并携带错误列表。
 */
public record ActionValidationResult(boolean accepted,
                                     List<String> errors,
                                     String riskLevel) {

    public static ActionValidationResult valid() {
        return new ActionValidationResult(true, List.of(), "NONE");
    }

    public static ActionValidationResult rejected(String... errors) {
        return new ActionValidationResult(false, List.of(errors), "HIGH");
    }

    public static ActionValidationResult rejected(List<String> errors) {
        return new ActionValidationResult(false, List.copyOf(errors), "HIGH");
    }
}
