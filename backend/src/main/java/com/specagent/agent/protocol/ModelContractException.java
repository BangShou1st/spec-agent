package com.specagent.agent.protocol;

/**
 * 文件名:ModelContractException.java
 *
 * 用途:模型适配器无法履行 Agent 契约时抛出的异常,例如当前激活的
 * 适配器不支持某种任务类型。
 */
public class ModelContractException extends RuntimeException {
    public ModelContractException(String message) {
        super(message);
    }
}