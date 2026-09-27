package com.specagent.agent.decision;

/**
 * 文件名:AgentBrainUnavailableException.java
 *
 * 用途:远程 Agent Brain 无法触达,或在返回可解析响应之前就失败时的类型化
 * 异常。运行时会把该异常映射到持久化的 run 失败路径;不会自动降级到其他
 * 规划器(planner)或提供方(provider)。
 *
 * {@link BrainFailureCode} 说明具体属于上述哪种原因,使持久化的失败记录和
 * 面向用户的文案保持真实,而不是把所有原因都笼统地报成 "brain unavailable"。
 */
public class AgentBrainUnavailableException extends RuntimeException {

    private final BrainFailureCode failureCode;

    public AgentBrainUnavailableException(String message, Throwable cause) {
        this(message, cause, BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    public AgentBrainUnavailableException(String message, Throwable cause,
                                          BrainFailureCode failureCode) {
        super(message, cause);
        this.failureCode = failureCode == null
                ? BrainFailureCode.BRAIN_UNAVAILABLE : failureCode;
    }

    /** 本次失败的归类原因;永不为 null。 */
    public BrainFailureCode failureCode() {
        return failureCode;
    }
}
