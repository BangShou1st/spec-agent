package com.specagent.agent.decision;

/**
 * 文件名:BrainFailureCode.java
 *
 * 用途:枚举 Brain 调用未能产出可用决策的各种原因。
 *
 * 在本类型出现之前,所有这些原因都会被折叠成单一的、不透明的
 * {@code brain_unavailable} run 失败,导致模型输出的确定性缺陷与服务宕机
 * 无法区分。reasonCode 是写入持久化 run 失败记录的稳定机器标识;reason
 * 字符串则是 eval harness 与 UI 读取的线上取值。
 *
 * 这些失败码都不授权自动重试:对给定的输出而言,契约(contract)与
 * 落地(grounding)类失败是确定性的,因此以类型化失败加人工恢复入口的方式
 * 呈现(run 可以从自身 checkpoint 恢复)。只有 Brain 内部那次由预算支付的
 * 冲突修复会重试模型调用,且该决策留在 Brain 内部。
 */
public enum BrainFailureCode {

    /** 连接不可达/被拒绝、未归类的 5xx,或空响应。 */
    BRAIN_UNAVAILABLE("brain_unavailable"),

    /** Brain 仍在处理时调用超时(连接超时或读取超时)。 */
    BRAIN_TIMEOUT("brain_timeout"),

    /** Brain 背后的模型提供方/代理(broker)调用失败。 */
    MODEL_PROVIDER_FAILURE("model_provider_failure"),

    /** 模型输出违反了 Brain 的输出契约。 */
    MODEL_CONTRACT_VIOLATION("model_contract_violation"),

    /** 模型引用了冻结快照允许 refs 之外的来源。 */
    MODEL_UNGROUNDED_REFERENCE("model_ungrounded_reference");

    private final String reasonCode;

    BrainFailureCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    /** 写入 run 轨迹和 {@code RUN_FAILED} 事件的稳定失败码。 */
    public String reasonCode() {
        return reasonCode;
    }
}
