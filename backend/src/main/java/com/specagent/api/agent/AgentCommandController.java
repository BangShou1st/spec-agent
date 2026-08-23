package com.specagent.api.agent;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Legacy agent command API.
 *
 * <p>After the answer and question-draft cutovers only spec generation remains
 * here. Answer/repair and DRAFT_QUESTION mutations go through
 * {@code AnswerCycleRunController} ({@code POST /agent-runs}, 202 + runId +
 * polling).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class AgentCommandController {

    private final AgentCommandService agentCommandService;

    public AgentCommandController(AgentCommandService agentCommandService) {
        this.agentCommandService = agentCommandService;
    }

    @PostMapping("/specs/generate")
    public SpecGenerationResponse generateSpec(@PathVariable UUID projectId) {
        return agentCommandService.generateSpec(projectId);
    }
}
