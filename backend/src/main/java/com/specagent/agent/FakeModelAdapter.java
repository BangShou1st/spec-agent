package com.specagent.agent;

import com.specagent.agent.contracts.AgentPlan;
import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.contracts.AnswerPatchDraft;
import com.specagent.agent.contracts.GapAnalysisResult;
import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.contracts.SpecDraft;
import com.specagent.common.Json;
import com.specagent.model.gateway.ModelGateway;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic fake model adapter used until a real model gateway exists.
 *
 * <p>It makes no HTTP calls, depends on no provider SDK, and produces fixed
 * outputs by task type so the agent loop can be tested before real models are
 * wired in. Unsupported task types are rejected with {@link ModelContractException}.
 *
 * <p>Implements {@link ModelGateway} and is the default selection of
 * {@code spec.agent.model.gateway} (the fake or unset value), so automated
 * tests keep running against the deterministic fake. The OpenCode gateway is
 * only registered when the property explicitly selects {@code opencode}.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.model.gateway", havingValue = "fake", matchIfMissing = true)
public class FakeModelAdapter implements ModelGateway {

    /**
     * Stable ids for the fake answer patch claims so the draft is fully
     * deterministic: the same request always serializes to the same output.
     * The fake adapter never fabricates real source ids; the orchestrator
     * supplies the real answered node and answer ids after the answer exists.
     */
    private static final UUID FAKE_CONFIRMED_CLAIM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID FAKE_UNRESOLVED_CLAIM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

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

            case INTERPRET_ANSWER -> response(
                    request,
                    AgentAction.INTERPRET_ANSWER,
                    json.write(new AnswerInterpretationResult(
                            List.of("The user clarified the main outcome."),
                            List.of(),
                            List.of("The user must confirm scope boundaries."),
                            List.of())));

            case DRAFT_ANSWER_PATCH -> response(
                    request,
                    AgentAction.INTERPRET_ANSWER,
                    json.write(new AnswerPatchDraft(
                            List.of(
                                    Claim.of(ClaimKind.GOAL,
                                            "The user clarified the main outcome.",
                                            ClaimStatus.CONFIRMED, null, null)
                                            .withId(FAKE_CONFIRMED_CLAIM_ID),
                                    Claim.of(ClaimKind.OPEN_QUESTION,
                                            "The user must confirm scope boundaries.",
                                            ClaimStatus.UNRESOLVED, null, null)
                                            .withId(FAKE_UNRESOLVED_CLAIM_ID)))));

            case DRAFT_SPEC -> response(
                    request,
                    AgentAction.GENERATE_SPEC,
                    json.write(new SpecDraft(
                            Map.of(
                                    "Overview", "The clarified requirement outcome.",
                                    "Open Questions", "Aspects still needing clarification."),
                            List.of("The user must confirm the primary outcome before final grounding."),
                            Map.of(
                                    "Overview", List.of("context:" + request.contextSnapshotId()),
                                    "Open Questions", List.of("context:" + request.contextSnapshotId())))));

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
                Map.of("adapter", "fake", "deterministic", "true",
                        "task", request.taskType().code()));
    }
}