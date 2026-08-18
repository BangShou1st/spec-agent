package com.specagent.api.agent;

import com.specagent.agent.FakeAnswerRunResult;
import com.specagent.api.node.NodeResponse;

/**
 * Result of an answer command (submit or repair): the completed run, the
 * immutable answer, the grounded answer patch, and the next drafted node.
 * Internal model contracts are never exposed.
 */
public record AnswerExecutionResponse(
        AgentRunResponse agentRun,
        AnswerResponse answer,
        AnswerPatchResponse answerPatch,
        NodeResponse nextNode) {

    public static AnswerExecutionResponse from(AgentRunResponse run,
                                               FakeAnswerRunResult result) {
        return new AnswerExecutionResponse(
                run,
                AnswerResponse.from(result.answer()),
                AnswerPatchResponse.from(result.patch()),
                NodeResponse.from(result.producedNode()));
    }
}