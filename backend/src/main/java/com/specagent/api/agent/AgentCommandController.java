package com.specagent.api.agent;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Legacy agent command API.
 *
 * <p>After the answer cutover only the flows that have not migrated to the
 * async Agent Runtime remain here (draft question, spec generation). Answer
 * and repair mutations go through {@code AnswerCycleRunController}
 * ({@code POST /agent-runs}, 202 + runId + polling).
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

    @PostMapping("/specs/generate")
    public SpecGenerationResponse generateSpec(@PathVariable UUID projectId) {
        return agentCommandService.generateSpec(projectId);
    }
}
