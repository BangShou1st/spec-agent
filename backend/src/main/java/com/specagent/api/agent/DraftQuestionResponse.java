package com.specagent.api.agent;

import com.specagent.api.node.NodeResponse;

/**
 * Result of a draft-next-question command: the completed agent run and the
 * node it produced. Raw model request/response/context material is never
 * exposed.
 */
public record DraftQuestionResponse(
        AgentRunResponse agentRun,
        NodeResponse producedNode) {
}