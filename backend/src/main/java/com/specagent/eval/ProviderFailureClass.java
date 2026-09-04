package com.specagent.eval;

/**
 * Coarse, provider-facing failure taxonomy for live evaluation reporting.
 *
 * <p>This taxonomy is deliberately separate from {@link FailureClass}: it is
 * used for reliability accounting and must never be treated as behavioral
 * quality.</p>
 */
public enum ProviderFailureClass {
    UNAUTHORIZED_401,
    RATE_LIMIT_429,
    SERVER_5XX,
    TIMEOUT,
    BRAIN_UNAVAILABLE,
    TRANSPORT_FAILURE,
    UNKNOWN
}
