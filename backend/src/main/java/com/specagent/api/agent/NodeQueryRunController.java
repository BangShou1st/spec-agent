package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.runtime.NodeQueryService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
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
 * Contextual AI query command surface ("ask AI about this node").
 *
 * <p>POST enqueues an async NODE_QUERY run (202 + runId); the worker executes
 * exactly one DECISION call. The route is explicit — a shared node never
 * falls back to an active/first/latest route to resolve its read context.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/nodes/{nodeId}/query")
public class NodeQueryRunController {

    private final RunService runService;
    private final AgentRunService agentRunService;
    private final AgentRunEventService eventService;

    public NodeQueryRunController(RunService runService,
                                  AgentRunService agentRunService,
                                  AgentRunEventService eventService) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.eventService = eventService;
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
                .<ResponseEntity<?>>map(run -> ResponseEntity.ok(queryResultView(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> queryResultView(AgentRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.id().toString());
        view.put("status", run.status().code());
        view.put("producedNodeId", run.producedNodeId() == null
                ? null : run.producedNodeId().toString());
        view.put("message", respondMessage(run.id()));
        return view;
    }

    private String respondMessage(UUID runId) {
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        return events.stream()
                .filter(e -> NodeQueryService.RESPOND_MESSAGE_EVENT.equals(e.eventType()))
                .map(e -> e.payload().get("message"))
                .filter(value -> value instanceof String)
                .map(String.class::cast)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public record NodeQueryRequest(UUID routeId, String question) {
    }
}
