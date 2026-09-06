package com.specagent.agent.loop;

import java.util.UUID;

/**
 * Autonomous continuation chain linkage carried on an {@code AgentRun} row.
 *
 * <p>{@code parentRunId} names the run whose terminal boundary spawned this
 * one; {@code rootRunId} names the chain head for querying;
 * {@code cycleIndex} counts the child depth with chain roots at 0. All
 * fields stay null for pre-continuation and external runs (lazy chain
 * identity: null reads as "own root at cycle 0"), so no existing creation
 * path changes.
 *
 * <p>Chain linkage only, never semantic state: no goal, conflict, or
 * planning content may enter this record.
 */
public record LoopLinkage(UUID parentRunId, UUID rootRunId, Integer cycleIndex) {

    /** Linkage for runs outside any continuation chain. */
    public static LoopLinkage none() {
        return new LoopLinkage(null, null, null);
    }
}
