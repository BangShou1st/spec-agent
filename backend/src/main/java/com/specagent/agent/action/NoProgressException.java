package com.specagent.agent.action;

/**
 * 文件名:NoProgressException.java
 *
 * 用途:决策循环检测到重复动作或无进展时抛出。Runtime 必须停止循环
 * 并如实上报该状态,而不是继续一个没有产出的循环。
 *
 * 协作:由决策循环的进度检测逻辑抛出,上层负责终止本次 run。
 */
public class NoProgressException extends RuntimeException {

    public NoProgressException(String message) {
        super(message);
    }
}
