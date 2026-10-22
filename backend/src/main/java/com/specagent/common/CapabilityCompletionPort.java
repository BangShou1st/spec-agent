package com.specagent.common;

import java.util.UUID;

/** Joins the caller's result transaction. The Host resolves scope from its durable rows. */
public interface CapabilityCompletionPort {
    void completed(UUID invocationId);
}
