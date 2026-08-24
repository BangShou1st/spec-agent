package com.specagent.agent.decision;

import com.specagent.agent.contract.AgentArtifactResponse;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;

/**
 * The decision engine port. One call represents one complete brain
 * operation; Reflection and Planning are never split into separate calls by
 * this boundary.
 *
 * <p>Implementations must return responses that already passed the fail-closed
 * {@link AgentBrainResponseValidator}; the runtime never consumes an
 * unvalidated brain response.
 */
public interface AgentDecisionEngine {

    /** Runs one STATE_UPDATE cycle: answer/evidence to grounded claims. */
    AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request);

    /** Runs one DECISION cycle: reflection + planning + primary action proposal. */
    AgentResponseEnvelope runDecision(AgentRequestEnvelope request);

    /**
     * Runs one ARTIFACT_GENERATION cycle: grounded context to a derived,
     * read-only artifact (initially only spec snapshots).
     */
    AgentArtifactResponse runArtifactGeneration(AgentRequestEnvelope request);
}
