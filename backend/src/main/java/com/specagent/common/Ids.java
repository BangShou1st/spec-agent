package com.specagent.common;

import java.util.UUID;

/**
 * Identifier generation for runtime records.
 *
 * <p>Deterministic within a run; uses random UUIDs so that persisted records
 * have stable, unique identity without relying on database sequence behavior.
 */
public final class Ids {

    private Ids() {
    }

    public static UUID random() {
        return UUID.randomUUID();
    }
}
