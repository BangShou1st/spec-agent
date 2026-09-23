package com.specagent.agent.snapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * Narrow read port for run attribution inside snapshot projection.
 *
 * <p>Consumer-owned (snapshot defines it, the run pipeline implements it) so
 * the snapshot package never depends on {@code agent.runtime}: the builder
 * only needs a run's route/node attribution for capability observations, not
 * the run repository itself.
 */
public interface RunAttributionLookupPort {

    /** Attribution projection carried by {@link CapabilityObservationVisibility.RunAttribution}. */
    Optional<CapabilityObservationVisibility.RunAttribution> attributionOf(UUID runId);
}
