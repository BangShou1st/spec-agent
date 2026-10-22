package com.specagent.agent.action;

import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.PendingDependencyProjection;

/** Host-owned, durable, currently authorized extension; pure contracts need no database. */
public interface ActionEligibilityDependencyPort {
    PendingDependencyProjection freeze(AgentRequestEnvelope request);
}
