package com.specagent.agent.contract;

import java.util.Set;

/**
 * Frozen protocol constants of the cross-language agent boundary.
 *
 * <p>These values are part of the versioned wire contract shared with the
 * Python agent brain (see {@code contracts/README.md}). Unknown protocol
 * versions are rejected fail-closed by both implementations.
 */
public final class AgentProtocol {

    /** Request envelope version sent by Spring to the Python brain. */
    public static final String INPUT_PROTOCOL_VERSION = "agent-input.v2";

    /** Response envelope version returned by the Python brain to Spring. */
    public static final String DECISION_PROTOCOL_VERSION = "agent-decision.v2";

    /** Response envelope version for derived artifact generation. */
    public static final String ARTIFACT_PROTOCOL_VERSION = "agent-artifact.v1";

    /** Internal model inference broker contract version (Python to Spring). */
    public static final String INFERENCE_PROTOCOL_VERSION = "model-inference.v1";

    /** Internal shared-secret header used in both directions. */
    public static final String INTERNAL_TOKEN_HEADER = "X-Spec-Agent-Internal-Token";

    /** Closed set of brain call types; the endpoint determines the call type. */
    public static final Set<String> CALL_TYPES = Set.of(
            "STATE_UPDATE", "DECISION", "ARTIFACT_GENERATION");

    /** Closed set of event kinds the runtime may send to the brain. */
    public static final Set<String> EVENT_KINDS = Set.of(
            "INITIAL", "CONTINUE", "ANSWER_SUBMITTED", "NODE_QUERY");

    private AgentProtocol() {
    }
}
