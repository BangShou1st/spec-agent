package com.specagent.agent.runtime;

/**
 * 文件名:StaleRunTargetException.java
 *
 * 用途:排队 run 记录的执行目标在认领时已与图的实际状态不符时抛出
 * (例如 run 排队等待期间项目切换到了另一个活跃 route)。此时周期必须
 * fail-closed,绝不能对用户早已不看的目标执行。
 */
public class StaleRunTargetException extends RuntimeException {

    public StaleRunTargetException(String message) {
        super(message);
    }
}
