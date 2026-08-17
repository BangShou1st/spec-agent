package com.specagent.agent;

import com.specagent.agent.contracts.AgentPlan;
import com.specagent.agent.contracts.GapAnalysisResult;
import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.common.Json;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Deterministic fake model adapter used until a real model gateway exists.
 *
 * <p>It makes no HTTP calls, depends on no provider SDK, and produces fixed
 * outputs by task type so the agent loop can be tested before real models are
 * wired in. Unsupported task types are rejected with {@link ModelContractException}.
 */
@Component
public class FakeModelAdapter {

    private final Json json;

    public FakeModelAdapter(Json json) {
        this.json = json;
    }

    public ModelResponse run(ModelRequest request) {
        if (request.contextSnapshotId() == null) {
            throw new ModelContractException("Fake model request requires contextSnapshotId");
        }

        return switch (request.taskType()) {
            case GAP_ANALYSIS -> response(
                    request,
                    AgentAction.ASK_NEXT_QUESTION,
                    json.write(new GapAnalysisResult(
                            List.of("initial clarification"),
                            List.of(),
                            List.of(),
                            false)));

            case PLAN_NEXT_ACTION -> response(
                    request,
                    AgentAction.ASK_NEXT_QUESTION,
                    json.write(new AgentPlan(
                            AgentAction.ASK_NEXT_QUESTION,
                            "Fake deterministic plan asks the next clarification question.")));

            case DRAFT_NODE -> response(
                    request,
                    AgentAction.ASK_NEXT_QUESTION,
                    json.write(new NodeDraft(
                            "What is the most important outcome?",
                            "This clarifies the primary requirement goal.",
                            List.of(),
                            true)));

            case REFLECT_NODE, REFLECT_PATCH, GROUND_SPEC -> response(
                    request,
                    AgentAction.STOP,
                    json.write(ReflectionResult.acceptedResult()));

            default -> throw new ModelContractException(
                    "FakeModelAdapter does not support task: " + request.taskType());
        };
    }

    private ModelResponse response(ModelRequest request, AgentAction action, String outputJson) {
        return new ModelResponse(
                request.agentRunId(),
                request.contextSnapshotId(),
                request.taskType(),
                action,
                outputJson,
                Map.of("adapter", "fake", "deterministic", "true"));
    }
}