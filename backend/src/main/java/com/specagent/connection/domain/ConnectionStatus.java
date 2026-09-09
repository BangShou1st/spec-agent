package com.specagent.connection.domain;

/**
 * Connection lifecycle status. Only TESTED/CONNECTED states may become
 * agent-visible; DISABLED/FAILED connections are invisible to planner
 * candidates by construction.
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