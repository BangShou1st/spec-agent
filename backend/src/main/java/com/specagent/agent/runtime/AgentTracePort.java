package com.specagent.agent.runtime;

import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.eligibility.ActionEligibilityGate;
import com.specagent.agent.policy.PolicyDecision;
import java.util.UUID;

/**
 * Write-side port for the optional semantic trace recorder.
 *
 * <p>The agent reasoning layer must not depend on the trace package: the
 * trace implementation consumes agent contract DTOs, so the natural
 * dependency direction is trace -> agent. This port inverts the write side
 * (agent -> trace) so the package pair stays acyclic. Implemented by
 * {@code com.specagent.trace.SemanticTraceRecorder}; disabled-by-default
 * semantics are the implementation's concern.
 */
public interface AgentTracePort {

    void captureStateUpdateInput(AgentRequestEnvelope request);

    void captureDecisionInput(AgentRequestEnvelope request);

    void captureStateUpdateOutput(AgentResponseEnvelope response);

    void captureDecisionOutput(AgentResponseEnvelope response);

    void capturePolicyDecision(UUID runId, PolicyDecision decision);

    void captureActionEligibility(UUID runId,
                                  AgentRequestEnvelope request,
                                  AgentResponseEnvelope response,
                                  ActionEligibilityGate.Assessment assessment);

    void capturePostState(UUID runId, AgentInputSnapshot postState);

    void captureFailure(UUID runId, String stage, Throwable error);
}
