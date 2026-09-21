package com.specagent.agent.runtime;

import java.util.UUID;

/**
 * The target route's tip carries a persisted Answer whose post-answer
 * processing never completed, so a derived artifact could only be a silently
 * incomplete document.
 *
 * <p>Thrown by the artifact cycle for a run that was already queued when the
 * tip became an unprocessed answer. The command surface rejects the same
 * situation up front with the same code, so the user gets one explanation and
 * one recovery entry (resume the saved answer, then generate).
 */
public class IncompleteAnswerCycleException extends RuntimeException {

    private final UUID answerId;
    private final UUID routeId;
    private final UUID nodeId;

    public IncompleteAnswerCycleException(String message) {
        this(message, null, null, null);
    }

    public IncompleteAnswerCycleException(String message, UUID answerId,
                                          UUID routeId, UUID nodeId) {
        super(message);
        this.answerId = answerId;
        this.routeId = routeId;
        this.nodeId = nodeId;
    }

    public UUID answerId() {
        return answerId;
    }

    public UUID routeId() {
        return routeId;
    }

    public UUID nodeId() {
        return nodeId;
    }
}
