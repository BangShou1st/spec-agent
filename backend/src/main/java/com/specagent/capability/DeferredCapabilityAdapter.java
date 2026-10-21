package com.specagent.capability;

import java.time.Duration;

/** Explicit opt-in for a recoverable external job. RUNNING alone never implies a job.
 * The adapter must durably submit using invocationKey as its downstream idempotency key,
 * return PENDING only after registration, and recheck current authority on completion.
 * No HTTP callback is exposed by this SPI. Synchronous and GA callers cannot opt in.
 */
public interface DeferredCapabilityAdapter extends GuardedCapabilityAdapter {
    Duration completionTimeout();
    void requireAuthorizedCompletion(CapabilityInvocationRecord invocation);
}
