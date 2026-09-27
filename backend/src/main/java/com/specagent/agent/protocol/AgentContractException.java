package com.specagent.agent.protocol;

/**
 * 文件名:AgentContractException.java
 *
 * 用途:在 Java ↔ Python 边界任一侧检测到跨语言契约违约时抛出的异常,
 * 包括:未知协议版本、未知字段、模型/Brain 凭空捏造 Runtime 独有的 id、
 * 引用了不允许的 source ref,以及其他一切 fail-closed 拒绝场景。
 */
public class AgentContractException extends com.specagent.agent.protocol.ModelContractException {

    public AgentContractException(String message) {
        super(message);
    }
}
