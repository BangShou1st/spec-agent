package com.specagent.agent.loop;

import java.util.UUID;

/**
 * One pending continuation evaluation: the terminal run plus the exact
 * request generation to complete.
 *
 * <p>The generation closes the approval re-request ABA race: every
 * {@code request(runId)} increments it, and completion marks exactly the
 * generation it evaluated. A stale generation marks 0 rows and leaves the
 * newer generation pending — never silently completing another request's
 * work. The coordinator still re-reads durable run facts to decide; this
 * record carries routing identity only, no semantic verdict.
 */
public record ContinuationCheck(UUID runId, long generation) {

    public ContinuationCheck {
        if (runId == null) {
            throw new IllegalArgumentException("Continuation check requires a run id");
        }
        if (generation < 1) {
            throw new IllegalArgumentException(
                    "Continuation check generation must be >= 1, got " + generation);
        }
    }
}
