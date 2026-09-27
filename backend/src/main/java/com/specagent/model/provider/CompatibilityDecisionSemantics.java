package com.specagent.model.provider;

/**
 * 文件名:CompatibilityDecisionSemantics.java
 *
 * 用途:提供商兼容性探测所需要的语义能力:判断一次原始补全是否是合法的
 * FINAL 决策。
 *
 * 探测协议(什么算"合法决策")属于 assistant 领域,但探测本身在 provider 层
 * 运行。依赖本端口而非具体的解析器/校验器,可以打破 model 与 globalassistant
 * 两个包之间的循环依赖;真正的适配器放在 assistant 侧,只负责转发
 * parse/validate/kind 三类检查。
 */
public interface CompatibilityDecisionSemantics {

    /** 把原始补全解析为不透明的决策对象;无法解析时抛异常。 */
    Object parseDecision(String content) throws Exception;

    /** 对 {@link #parseDecision} 产出的决策做权威校验。 */
    void validateDecision(Object decision) throws Exception;

    /** 判断决策是否携带探测所需的 FINAL 类型。 */
    boolean isFinal(Object decision);
}
