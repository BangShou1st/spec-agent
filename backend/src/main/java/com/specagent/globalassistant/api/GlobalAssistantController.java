package com.specagent.globalassistant.api;

import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.stream.GlobalAssistantStreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Backend V1 public contracts. HTTP/SSE only; no domain logic here.
 */
@RestController
@RequestMapping("/api/v1/global-assistant")
public class GlobalAssistantController {
    private final GlobalAssistantApplicationService application;
    private final GlobalAssistantRunEventRepository events;
    private final GlobalAssistantStreamService streams;
    public GlobalAssistantController(GlobalAssistantApplicationService application,
            GlobalAssistantRunEventRepository events, GlobalAssistantStreamService streams) {
        this.application = application;
        this.events = events;
        this.streams = streams;
    }
    public record CreateThreadResponse(String threadId) {
    }
    public record CreateRunRequest(@NotBlank String message, UiContextDto uiContext) {
        public record UiContextDto(String currentPage, SelectedEntityDto selectedEntity) {
            public record SelectedEntityDto(String type, String id) {
            }
        }
    }
    public record CreateRunResponse(String runId, String status) {
    }
    public record ThreadResponse(String threadId, String summary, int summaryVersion,
            int workingStateVersion, String createdAt, String updatedAt) {
    }
    public record MessageResponse(String id, String threadId, String role, String content,
            String runId, String createdAt) {
    }
    public record RunResponse(String runId, String threadId, String status, int stepCount,
            String cancelRequestedAt, String startedAt, String completedAt, String errorCode) {
    }
    @PostMapping("/threads")
    public CreateThreadResponse createThread() {
        GlobalAssistantThread thread = application.createThread();
        return new CreateThreadResponse(thread.id().toString());
    }
    @PostMapping("/threads/{threadId}/runs")
    public CreateRunResponse createRun(@PathVariable UUID threadId,
            @Valid @RequestBody CreateRunRequest request) {
        GlobalAssistantContextBuilder.UiRequest uiRequest = null;
        if (request.uiContext() != null) {
            GlobalAssistantContextBuilder.UiRequest.SelectedRef selected = null;
            if (request.uiContext().selectedEntity() != null) {
                selected = new GlobalAssistantContextBuilder.UiRequest.SelectedRef(
                        request.uiContext().selectedEntity().type(),
                        request.uiContext().selectedEntity().id());
            }
            uiRequest = new GlobalAssistantContextBuilder.UiRequest(
                    request.uiContext().currentPage(), selected);
        }
        GlobalAssistantRun run = application.createRun(threadId, request.message(), uiRequest);
        return new CreateRunResponse(run.id().toString(), run.status().name());
    }
    @GetMapping("/threads/{threadId}")
    public ThreadResponse getThread(@PathVariable UUID threadId) {
        GlobalAssistantThread thread = application.requireThread(threadId);
        return new ThreadResponse(thread.id().toString(), thread.summary(),
                thread.summaryVersion(), thread.workingStateVersion(),
                thread.createdAt().toString(), thread.updatedAt().toString());
    }
    @GetMapping("/threads/{threadId}/messages")
    public List<MessageResponse> listMessages(@PathVariable UUID threadId) {
        List<GlobalAssistantMessage> stored = application.listMessages(threadId);
        return stored.stream().map(m -> new MessageResponse(m.id().toString(),
                m.threadId().toString(), m.role().name(), m.content(),
                m.runId() == null ? null : m.runId().toString(), m.createdAt().toString())).toList();
    }
    @GetMapping("/runs/{runId}")
    public RunResponse getRun(@PathVariable UUID runId) {
        GlobalAssistantRun run = application.requireRun(runId);
        return new RunResponse(run.id().toString(), run.threadId().toString(),
                run.status().name(), run.stepCount(),
                run.cancelRequestedAt() == null ? null : run.cancelRequestedAt().toString(),
                run.startedAt().toString(),
                run.completedAt() == null ? null : run.completedAt().toString(),
                run.errorCode());
    }
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> listEvents(@PathVariable UUID runId) {
        application.requireRun(runId);
        return streams.listEnvelopes(runId);
    }
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable UUID runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        application.requireRun(runId);
        Integer cursor = null;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                cursor = Integer.parseInt(lastEventId.trim());
            } catch (NumberFormatException ignored) {
                cursor = null;
            }
        }
        return streams.subscribe(runId, cursor);
    }
    @PostMapping("/runs/{runId}/cancel")
    public RunResponse cancelRun(@PathVariable UUID runId) {
        GlobalAssistantRun run = application.cancelRun(runId);
        return new RunResponse(run.id().toString(), run.threadId().toString(),
                run.status().name(), run.stepCount(),
                run.cancelRequestedAt() == null ? null : run.cancelRequestedAt().toString(),
                run.startedAt().toString(),
                run.completedAt() == null ? null : run.completedAt().toString(),
                run.errorCode());
    }
}
