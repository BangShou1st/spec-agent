package com.specagent.agent.broker;

import java.util.UUID;

/**
 * Port interface for resolving the owning project of a run before the internal
 * inference broker processes a request.
 *
 * <p>Provider-side conversation identity is per project, so the broker needs the
 * project of the run it is serving. The implementation lives in the runtime
 * package and delegates to durable persistence, keeping the broker free of
 * repository dependencies.
 *
 * <p>An unknown run returns {@code null}. Callers treat that as "no project
 * affinity" and fall back to the run, never as a request failure: a run that
 * cannot be resolved here was already rejected by {@link RunExistenceCheck}.
 */
@FunctionalInterface
public interface RunProjectLookup {

    /** Owning project of the run, or {@code null} when it cannot be resolved. */
    UUID projectIdOf(UUID runId);
}
