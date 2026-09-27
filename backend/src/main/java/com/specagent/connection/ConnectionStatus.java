package com.specagent.connection;

/**
 * 文件名:ConnectionStatus.java
 *
 * 用途:Connection 生命周期的状态枚举,描述连接从创建到可用的演进。
 *
 * 只有 TESTED/CONNECTED 状态才可能对 agent 可见;DISABLED/FAILED 状态的
 * 连接从构造上就不会进入 planner 候选。
 */
public enum ConnectionStatus {

    CREATED("CREATED"),
    TESTED("TESTED"),
    CONNECTED("CONNECTED"),
    DISABLED("DISABLED"),
    FAILED("FAILED");

    private final String code;

    ConnectionStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ConnectionStatus fromCode(String code) {
        for (ConnectionStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown connection status: " + code);
    }

    public boolean agentVisible() {
        return this == TESTED || this == CONNECTED;
    }
}