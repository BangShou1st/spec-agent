package com.specagent.common;

/** Bounded reconciliation of durable business outcomes into the existing Host outbox. */
public interface DeferredCompletionRecoveryPort {
    void recover(int limit);
}
