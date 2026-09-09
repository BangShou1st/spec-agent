package com.specagent.globalassistant.turn;

/**
 * Durable steer lifecycle. Only PENDING/CLAIMED count as unresolved.
 */
public enum PendingTurnStatus {
    PENDING,
    CLAIMED,
    CONSUMED,
    DISCARDED;

    public static PendingTurnStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Pending turn status must not be null");
        }
        return valueOf(code.trim().toUpperCase());
    }

    public boolean isUnresolved() {
        return this == PENDING || this == CLAIMED;
    }
}
