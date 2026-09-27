package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunEventTypes;
import com.specagent.agent.policy.AgentProposalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:NodeQueryRunController.java
 *
 * 用途:"针对节点问 AI"的上下文查询命令面。
 *
 * POST 入队一个异步 NODE_QUERY run(返回 202 + runId);worker 恰好执行
 * 一次 DECISION 调用。路由必须显式给出——共享节点绝不回退到
 * 活动/第一个/最新的路由来解析其读取上下文。
 *
 * 协作:由前端 Inspector 调用;查询结果视图由本控制器的
 * queryResultView 组装,含提案状态与语义化的终态。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/nodes/{nodeId}/query")
public class NodeQueryRunController {

    private final RunService runService;
    private final AgentRunService agentRunService;
    private final AgentRunEventService eventService;
    private final AgentProposalService proposalService;

    public NodeQueryRunController(RunService runService,
                                  AgentRunService agentRunService,
                                  AgentRunEventService eventService,
                                  AgentProposalService proposalService) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.eventService = eventService;
        this.proposalService = proposalService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createQuery(@PathVariable UUID projectId,
                                                           @PathVariable UUID nodeId,
                                                           @RequestBody NodeQueryRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "question must not be blank"));
        }
        UUID runId = runService.createQueuedNodeQuery(
                projectId, request.routeId(), nodeId, request.question().trim());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "runId", runId.toString(),
                "phase", "CREATED"));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> getQueryResult(@PathVariable UUID projectId,
                                            @PathVariable UUID nodeId,
                                            @PathVariable UUID runId) {
        return agentRunService.getRun(runId)
                .filter(run -> run.projectId().equals(projectId))
                .filter(run -> run.triggerType().code().equals("node_query"))
                // 该 run 必须指向请求的节点。inputNodeId 不匹配(或为 null)的
                // node_query run 绝不能被提供给其他节点——fail-closed 返回 404。
                .filter(run -> nodeId.equals(run.inputNodeId()))
                .<ResponseEntity<?>>map(run -> ResponseEntity.ok(queryResultView(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> queryResultView(AgentRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.id().toString());
        view.put("producedNodeId", run.producedNodeId() == null
                ? null : run.producedNodeId().toString());
        view.put("message", respondMessage(run.id()));

        // 如果查询被降级为等待批准的动作,则把本 run 产出的 advisory 提案
        // 暴露出来。只读 run 没有提案。语义化状态反映提案生命周期,
        // 供前端渲染 awaiting/accepted/rejected 界面(B3 收尾)。
        var proposal = proposalService.findByRunId(run.id());
        if (proposal.isPresent()) {
            var p = proposal.get();
            view.put("proposalId", p.id().toString());
            view.put("proposalStatus", p.status().code());
            view.put("actionFamily", p.actionFamily());
            view.put("status", switch (p.status()) {
                case PROPOSED -> "AWAITING_APPROVAL";
                case ACCEPTED, MODIFIED -> "ACCEPTED";
                case REJECTED -> "REJECTED";
                default -> p.status().code();
            });
            return view;
        }

        // 无提案时:终态从 DURABLE 的 runtime 事件证据推导,绝不依赖人类可读的
        // trace 字符串。提案被策略拒绝、或变更动作无法确认的查询 run,
        // 保留其语义化结果,而不是坍缩成 COMPLETED。
        List<AgentRunEvent> events = eventService.findByRunId(run.id());
        boolean policyDenied = events.stream()
                .anyMatch(e -> AgentRunEventTypes.POLICY_DENIED_EVENT.equals(e.eventType()));
        boolean notConfirmable = events.stream()
                .anyMatch(e -> AgentRunEventTypes.MUTATION_NOT_CONFIRMABLE_EVENT.equals(e.eventType()));
        view.put("proposalId", null);
        view.put("proposalStatus", null);
        view.put("actionFamily", null);
        if (policyDenied) {
            view.put("status", "POLICY_DENIED");
        } else if (notConfirmable) {
            view.put("status", "NOT_CONFIRMABLE");
        } else if (run.status() == AgentRunStatus.FAILED) {
            view.put("status", "FAILED");
        } else {
            // 领域模型中的 run 状态码是小写;查询结果契约对终态 run 状态
            // 使用大写的前端命名(上面那些语义化状态本身就是大写)。
            view.put("status", run.status().code().toUpperCase());
        }
        return view;
    }

    private String respondMessage(UUID runId) {
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        return events.stream()
                .filter(e -> AgentRunEventTypes.RESPOND_MESSAGE_EVENT.equals(e.eventType()))
                .map(e -> e.payload().get("message"))
                .filter(value -> value instanceof String)
                .map(String.class::cast)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public record NodeQueryRequest(UUID routeId, String question) {
    }
}
