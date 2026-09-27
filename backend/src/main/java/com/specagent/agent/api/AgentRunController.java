package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.common.ApiException;
import com.specagent.workspace.project.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:AgentRunController.java
 *
 * 用途:面向运维者的 agent run 只读 REST API。
 *
 * 保持 Phase 6.1 的读取契约:任何端点都不启动、应答或变更 agent run。
 * 会校验 run/项目归属关系,项目 A 的 run 绝不可能通过项目 B 读取。
 * 仅暴露安全的元数据和脱敏后的 trace 步骤列表。
 *
 * 协作:由运维/调试前端调用,数据经 AgentRunDtoMapper 转为响应 DTO。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final ProjectService projectService;
    private final AgentRunDtoMapper agentRunDtoMapper;

    public AgentRunController(AgentRunService agentRunService,
                              ProjectService projectService,
                              AgentRunDtoMapper agentRunDtoMapper) {
        this.agentRunService = agentRunService;
        this.projectService = projectService;
        this.agentRunDtoMapper = agentRunDtoMapper;
    }

    @GetMapping
    public List<AgentRunResponse> listRuns(@PathVariable UUID projectId) {
        projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        return agentRunService.listByProject(projectId).stream()
                .map(agentRunDtoMapper::from)
                .toList();
    }

    @GetMapping("/{runId}")
    public AgentRunResponse getRun(@PathVariable UUID projectId, @PathVariable UUID runId) {
        AgentRun run = agentRunService.getRun(runId)
                .orElseThrow(() -> ApiException.notFound("RUN_NOT_FOUND", "Agent run not found"));
        if (!run.projectId().equals(projectId)) {
            throw ApiException.notFound("RUN_NOT_FOUND", "Agent run not found");
        }
        return agentRunDtoMapper.from(run);
    }
}