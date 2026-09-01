package com.specagent.agent.snapshot;

/**
 * A semantic replay was requested for a snapshot that was already consumed by
 * the model before the frozen-input contract existed. Rebuilding the old
 * model input from current live records and pretending it is a replay would
 * break reproducibility and auditability.
 *
 * <p>Callers must fail closed with this typed domain failure — no second
 * Answer, no second Patch, no silent STATE_UPDATE rerun, and no live
 * reconstruction of the old DECISION input. A fresh retry must be started
 * from a new ContextSnapshot instead.
 */
public class LegacyFrozenInputUnavailableException extends RuntimeException {

    public LegacyFrozenInputUnavailableException(String message) {
        super(message);
    }
}
