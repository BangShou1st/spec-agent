package com.specagent.connection.domain;

/**
 * Connection kind. {@code SYSTEM_SUPPORTED} is a product-managed integration
 * (e.g. GitHub) with a defined extension point; {@code CUSTOM_MCP} is a
 * user-supplied remote MCP server. Connection is the product concept; MCP is
 * the protocol implementation — the two are never fused into one class.
 */
public enum ConnectionKind {

    SYSTEM_SUPPORTED("SYSTEM_SUPPORTED"),
    CUSTOM_MCP("CUSTOM_MCP");

    private final String code;

    ConnectionKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ConnectionKind fromCode(String code) {
        for (ConnectionKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown connection kind: " + code);
    }
}