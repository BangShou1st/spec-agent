package com.specagent.agent.api;

import com.specagent.agent.runtime.AcceptedRunView;
import com.specagent.agent.runtime.CreateRunRequest;

import com.specagent.agent.runtime.AcceptedRunView;
import com.specagent.agent.runtime.AnswerCycleRunCommandService;
import com.specagent.agent.runtime.CreateRunRequest;
import com.specagent.workspace.route.CommandExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 文件名:AnswerCycleRunController.java
 *
 * 用途:异步 agent run 的命令 + 轮询 REST API。编排逻辑位于
 * {@link AnswerCycleRunCommandService};本类只承载 HTTP 契约。
 *
 * 协作:前端通过 POST 创建 run、GET 轮询单个 run 状态,
 * 并通过 /active 端点恢复进行中的 run 注册表。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs")
public class AnswerCycleRunController {

    private final AnswerCycleRunCommandService commandService;
    private final com.specagent.agent.runtime.AgentRunService agentRunService;

    public AnswerCycleRunController(AnswerCycleRunCommandService commandService,
                                    com.specagent.agent.runtime.AgentRunService agentRunService) {
        this.commandService = commandService;
        this.agentRunService = agentRunService;
    }

    @PostMapping
    public ResponseEntity<AcceptedRunView> createRun(
            @PathVariable UUID projectId,
            @RequestBody CreateRunRequest request) {
        return CommandExecution.execute(() -> commandService.createRun(projectId, request));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> getRun(@PathVariable UUID projectId,
                                    @PathVariable UUID runId) {
        return agentRunService.getRun(runId)
                .filter(run -> run.projectId().equals(projectId))
                .<ResponseEntity<?>>map(run -> ResponseEntity.ok(commandService.runView(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** 项目下全部未到达终态的 run;供前端在页面刷新后重建进行中的 run 注册表。 */
    @GetMapping("/active")
    public ResponseEntity<?> listActiveRuns(@PathVariable UUID projectId) {
        return ResponseEntity.ok(agentRunService.listActiveByProject(projectId).stream()
                .map(commandService::runView)
                .toList());
    }
}
