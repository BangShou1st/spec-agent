package com.specagent.api.agent;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Agent execution command API.
 *
 * <p>Every command goes through {@link AgentCommandService} and the existing
 * orchestrator; the controller never calls the model gateway, never builds a
 * {@code ContextSnapshot}, and never persists runtime records. Execution stays
 * synchronous in Phase 6.2.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class AgentCommandController {

    private final AgentCommandService agentCommandService;

    public AgentCommandController(AgentCommandService agentCommandService) {
        this.agentCommandService = agentCommandService;
    }

    @PostMapping("/questions/next")
    public DraftQuestionResponse draftNextQuestion(@PathVariable UUID projectId) {
        return agentCommandService.draftNext(projectId);
    }

    @PostMapping("/answers")
    public AnswerExecutionResponse submitAnswer(@PathVariable UUID projectId,
                                                @Valid @RequestBody SubmitAnswerRequest request) {
        return agentCommandService.submitAnswer(projectId, request);
    }

    @PostMapping("/answers/{answerId}/repair")
    public AnswerExecutionResponse repairAnswer(@PathVariable UUID projectId,
                                                @PathVariable UUID answerId) {
        return agentCommandService.repairAnswer(projectId, answerId);
    }

    @PostMapping("/specs/generate")
    public SpecGenerationResponse generateSpec(@PathVariable UUID projectId) {
        return agentCommandService.generateSpec(projectId);
    }
}