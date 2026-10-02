package com.specagent.model.provider;

/** Provider compatibility probe JSON semantics, independent of executable assistant protocols. */
public interface CompatibilityDecisionSemantics {

    /** 把原始补全解析为不透明的决策对象;无法解析时抛异常。 */
    Object parseDecision(String content) throws Exception;

    /** 对 {@link #parseDecision} 产出的决策做权威校验。 */
    void validateDecision(Object decision) throws Exception;

    /** 判断决策是否携带探测所需的 FINAL 类型。 */
    boolean isFinal(Object decision);
}
