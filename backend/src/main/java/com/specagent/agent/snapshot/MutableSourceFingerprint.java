package com.specagent.agent.snapshot;

import java.util.UUID;

/**
 * Fingerprint of one model-visible mutable source captured at freeze time.
 *
 * <p>Used to decide whether a stored proposal's live execution is still
 * eligible: every frozen fingerprint is re-derived from the current
 * authoritative state before execution/acceptance and compared for equality.
 * A mismatch on any relevant source raises {@code STALE_CONTEXT} and no
 * graph mutation is applied.
 *
 * <p>Only sources that were actually visible to the model at freeze time are
 * fingerprinted — a mutation of an unrelated workspace entity never marks a
 * proposal stale.
 */
public record MutableSourceFingerprint(String sourceType,
                                       UUID sourceId,
                                       String contentHash) {
}
