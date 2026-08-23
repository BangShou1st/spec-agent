package com.specagent.agent.action;

import java.util.UUID;

/**
 * Outcome of executing an action proposal. Carries the action family that
 * was executed and any produced runtime artifacts (node, answer, message).
 */
public record ActionResult(String actionFamily,
                           UUID producedNodeId,
                           UUID producedAnswerId,
                           String message) {
}
