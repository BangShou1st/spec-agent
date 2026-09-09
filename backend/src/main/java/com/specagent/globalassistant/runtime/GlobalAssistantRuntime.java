package com.specagent.globalassistant.runtime;

import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.common.Ids;
import com.specagent.globalassistant.context.GlobalAssistantContext;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantEventType;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
import com.specagent.globalassistant.conversation.GlobalAssistantWorkingState;
import com.specagent.globalassistant.model.GlobalAssistantBrain;
import com.specagent.globalassistant.model.GlobalAssistantDecision;
import com.specagent.globalassistant.model.GlobalAssistantModelException;
import com.specagent.globalassistant.stream.GlobalAssistantStreamService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded sequential tool-agent loop. Owns orchestration only:
 * decision -&gt; tool -&gt; observation -&gt; decision, plus cancel, budgets,
 * no-progress, persistence coordination and terminalization.
 */
@Service
public class GlobalAssistantRuntime {
    private final GlobalAssistantConversationService conversations;
    private final GlobalAssistantContextBuilder contextBuilder;
    private final GlobalAssistantBrain brain;
    private final CapabilityRuntime capabilities;
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRunEventRepository events;
    private final GlobalAssistantStreamService streams;
    private final GlobalAssistantToolArgumentCanonicalizer canonicalizer;
    private final GlobalAssistantRuntimeProperties budgets;
    public GlobalAssistantRuntime(GlobalAssistantConversationService conversations,
            GlobalAssistantContextBuilder contextBuilder, GlobalAssistantBrain brain,
            CapabilityRuntime capabilities, GlobalAssistantRunRepository runs,
            GlobalAssistantRunEventRepository events, GlobalAssistantStreamService streams,
            GlobalAssistantToolArgumentCanonicalizer canonicalizer,
            GlobalAssistantRuntimeProperties budgets) {
        this.conversations = conversations;
        this.contextBuilder = contextBuilder;
        this.brain = brain;
        this.capabilities = capabilities;
        this.runs = runs;
        this.events = events;
        this.streams = streams;
        this.canonicalizer = canonicalizer;
        this.budgets = budgets;
    }
    /**
     * Executes one user turn synchronously. The run row already exists;
     * this method drives it to a terminal state.
     */
    public void executeRun(UUID threadId, UUID runId, String userMessage,
            GlobalAssistantContextBuilder.UiRequest uiRequest) {
        appendEvent(runId, GlobalAssistantEventType.RUN_STARTED, Map.of("threadId", threadId.toString()));
        markRunning(runId);
        List<Map<String, Object>> observations = new ArrayList<>();
        List<GlobalAssistantObservation> typedObservations = new ArrayList<>();
        String lastCapability = null;
        String lastCanonicalArgs = null;
        int observationFingerprint = 0;
        int toolCalls = 0;
        int steps = 0;
        while (true) {
            if (isCancelRequested(runId)) {
                terminalizeCancelled(threadId, runId);
                return;
            }
            if (steps >= budgets.maxSteps()) {
                failRun(threadId, runId, GlobalAssistantErrorCode.RUN_STEP_LIMIT,
                        "Step budget exhausted", observations);
                return;
            }
            GlobalAssistantContext context = contextBuilder.build(threadId, userMessage, uiRequest);
            GlobalAssistantDecision decision;
            try {
                decision = brain.decide(runId, context, observations);
            } catch (GlobalAssistantModelException ex) {
                failRun(threadId, runId, ex.errorCode(), ex.getMessage(), observations);
                return;
            } catch (RuntimeException ex) {
                failRun(threadId, runId, GlobalAssistantErrorCode.MODEL_UNAVAILABLE,
                        "Model unavailable", observations);
                return;
            }
            incrementStep(runId);
            steps++;
            if (decision.toolRequest() != null) {
                if (toolCalls >= budgets.maxToolCalls()) {
                    failRun(threadId, runId, GlobalAssistantErrorCode.RUN_STEP_LIMIT,
                            "Tool budget exhausted", observations);
                    return;
                }
                String canonicalArgs = canonicalizer.canonicalize(decision.toolRequest().arguments());
                if (lastCapability != null && lastCapability.equals(decision.toolRequest().capabilityId())
                        && lastCanonicalArgs != null && lastCanonicalArgs.equals(canonicalArgs)) {
                    int currentFingerprint = observations.hashCode() + workingStateFingerprint(threadId);
                    if (currentFingerprint == observationFingerprint) {
                        String text = decision.assistantText() != null && !decision.assistantText().isBlank()
                                ? decision.assistantText()
                                : "The same lookup was already tried without new results, so I stopped here.";
                        completeWithText(threadId, runId, text, decision.uiAction());
                        return;
                    }
                }
                if (isCancelRequested(runId)) {
                    terminalizeCancelled(threadId, runId);
                    return;
                }
                String statusLabel = statusLabelFor(decision.toolRequest().capabilityId());
                appendEvent(runId, GlobalAssistantEventType.STATUS, Map.of("message", statusLabel));
                appendEvent(runId, GlobalAssistantEventType.TOOL_STARTED, Map.of(
                        "capabilityId", decision.toolRequest().capabilityId(),
                        "arguments", sanitizedArgs(decision.toolRequest().arguments())));
                CapabilityResult result = executeTool(runId, toolCalls, decision);
                toolCalls++;
                GlobalAssistantObservation observation = toObservation(decision.toolRequest().capabilityId(), result);
                typedObservations.add(observation);
                observations.add(observation.toMap());
                updateWorkingState(threadId, decision.toolRequest().capabilityId(), result);
                if (result.status() == CapabilityResult.Status.SUCCEEDED
                        || result.status() == CapabilityResult.Status.REPLAYED) {
                    appendEvent(runId, GlobalAssistantEventType.TOOL_COMPLETED, Map.of(
                            "capabilityId", decision.toolRequest().capabilityId(),
                            "summary", toolSummary(decision.toolRequest().capabilityId(), result)));
                } else if (result.status() == CapabilityResult.Status.IN_PROGRESS) {
                    appendEvent(runId, GlobalAssistantEventType.TOOL_FAILED, Map.of(
                            "capabilityId", decision.toolRequest().capabilityId(),
                            "errorCode", GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "reason", "Tool is still running; stopping the loop honestly."));
                    failRun(threadId, runId, GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "Tool still in progress", observations);
                    return;
                } else {
                    String errorCode = isNotFound(result)
                            ? GlobalAssistantErrorCode.PROJECT_NOT_FOUND
                            : GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED;
                    appendEvent(runId, GlobalAssistantEventType.TOOL_FAILED, Map.of(
                            "capabilityId", decision.toolRequest().capabilityId(),
                            "errorCode", errorCode,
                            "reason", sanitizedReason(result)));
                }
                lastCapability = decision.toolRequest().capabilityId();
                lastCanonicalArgs = canonicalArgs;
                observationFingerprint = observations.hashCode() + workingStateFingerprint(threadId);
                if (decision.done() && decision.toolRequest() != null && decision.assistantText() != null
                        && !decision.assistantText().isBlank() && toolCalls > 0) {
                    // Model asked for a tool and already has final text: continue one more
                    // decision so the text is emitted after the observation.
                }
                continue;
            }
            if (decision.requiresUserInput()) {
                String question = decision.assistantText() != null && !decision.assistantText().isBlank()
                        ? decision.assistantText()
                        : "Could you clarify which project you mean?";
                persistAssistant(threadId, runId, question);
                appendEvent(runId, GlobalAssistantEventType.ASSISTANT_DELTA, Map.of("text", question));
                appendEvent(runId, GlobalAssistantEventType.ASSISTANT_COMPLETED, Map.of());
                appendEvent(runId, GlobalAssistantEventType.USER_INPUT_REQUIRED,
                        Map.of("question", question));
                if (decision.uiAction() != null) {
                    emitUiAction(runId, decision.uiAction());
                }
                terminalizeCompleted(threadId, runId);
                return;
            }
            if (decision.done() || decision.uiAction() != null
                    || (decision.assistantText() != null && !decision.assistantText().isBlank())) {
                String text = decision.assistantText() != null ? decision.assistantText() : "";
                if (decision.uiAction() != null) {
                    try {
                        emitUiAction(runId, decision.uiAction());
                    } catch (GlobalAssistantModelException ex) {
                        failRun(threadId, runId, GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                                ex.getMessage(), observations);
                        return;
                    }
                    if (text.isBlank()) {
                        text = defaultNavigationText(decision.uiAction());
                    }
                }
                if (text.isBlank()) {
                    text = "Done.";
                }
                completeWithText(threadId, runId, text, null);
                return;
            }
            failRun(threadId, runId, GlobalAssistantErrorCode.MODEL_INVALID_RESPONSE,
                    "Indecisive model response", observations);
            return;
        }
    }
    private CapabilityResult executeTool(UUID runId, int toolIndex, GlobalAssistantDecision decision) {
        String invocationKey = "ga:" + runId + ":" + toolIndex + ":" + Ids.random();
        try {
            return capabilities.invokeApplicationScoped(invocationKey,
                    decision.toolRequest().capabilityId(), runId, decision.toolRequest().arguments());
        } catch (RuntimeException ex) {
            return CapabilityResult.failed(Ids.random(), invocationKey,
                    decision.toolRequest().capabilityId(), "Capability execution failed");
        }
    }
    private GlobalAssistantObservation toObservation(String capabilityId, CapabilityResult result) {
        if (result.status() == CapabilityResult.Status.SUCCEEDED
                || result.status() == CapabilityResult.Status.REPLAYED) {
            Map<String, Object> data = new LinkedHashMap<>(result.content());
            return new GlobalAssistantObservation(capabilityId, true, data, null);
        }
        if (result.status() == CapabilityResult.Status.IN_PROGRESS) {
            return new GlobalAssistantObservation(capabilityId, false, Map.of(), GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED);
        }
        String reason = sanitizedReason(result);
        String code = reason.contains("not found") || reason.contains("Not found")
                ? GlobalAssistantErrorCode.PROJECT_NOT_FOUND
                : GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED;
        if (capabilityId.equals("project.get_summary") && reason.contains("Project not found")) {
            code = GlobalAssistantErrorCode.PROJECT_NOT_FOUND;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        return new GlobalAssistantObservation(capabilityId, false, data, code);
    }
    @Transactional
    public void updateWorkingState(UUID threadId, String capabilityId, CapabilityResult result) {
        GlobalAssistantWorkingState current = conversations.readWorkingState(threadId);
        var thread = conversations.findThread(threadId).orElseThrow();
        GlobalAssistantWorkingState next = evolveWorkingState(current, capabilityId, result);
        try {
            conversations.writeWorkingState(threadId, next, thread.workingStateVersion());
        } catch (IllegalStateException ex) {
            GlobalAssistantWorkingState fresh = conversations.readWorkingState(threadId);
            var latest = conversations.findThread(threadId).orElseThrow();
            conversations.writeWorkingState(threadId, evolveWorkingState(fresh, capabilityId, result),
                    latest.workingStateVersion());
        }
    }
    @SuppressWarnings("unchecked")
    private GlobalAssistantWorkingState evolveWorkingState(GlobalAssistantWorkingState current,
            String capabilityId, CapabilityResult result) {
        if (result.status() != CapabilityResult.Status.SUCCEEDED
                && result.status() != CapabilityResult.Status.REPLAYED) {
            return current;
        }
        Map<String, Object> content = result.content();
        List<Map<String, String>> candidates = new ArrayList<>(current.candidateProjects());
        UUID resolved = current.lastResolvedProjectId();
        List<String> refs = new ArrayList<>(current.lastToolResultRefs());
        if ("project.search".equals(capabilityId) || "project.list_recent".equals(capabilityId)) {
            Object raw = "project.search".equals(capabilityId) ? content.get("candidates") : content.get("projects");
            candidates = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Object id = m.get("projectId");
                        Object title = m.get("title");
                        if (id != null && title != null) {
                            candidates.add(Map.of("projectId", String.valueOf(id), "title", String.valueOf(title)));
                        }
                    }
                    if (candidates.size() >= 10) {
                        break;
                    }
                }
            }
            if (candidates.size() == 1) {
                try {
                    resolved = UUID.fromString(candidates.get(0).get("projectId"));
                } catch (IllegalArgumentException ignored) {
                }
            }
            refs.add(capabilityId + ":" + candidates.size());
        } else if ("project.create".equals(capabilityId)) {
            Object id = content.get("projectId");
            if (id != null) {
                try {
                    resolved = UUID.fromString(String.valueOf(id));
                } catch (IllegalArgumentException ignored) {
                }
            }
            refs.add("project.create:" + id);
        } else if ("project.get_summary".equals(capabilityId)) {
            Object id = content.get("projectId");
            if (id != null) {
                try {
                    resolved = UUID.fromString(String.valueOf(id));
                } catch (IllegalArgumentException ignored) {
                }
            }
            refs.add("project.get_summary:" + id);
        }
        if (refs.size() > 20) {
            refs = refs.subList(refs.size() - 20, refs.size());
        }
        return new GlobalAssistantWorkingState(current.goal(), candidates, current.waitingFor(), resolved, refs);
    }
    private int workingStateFingerprint(UUID threadId) {
        try {
            return conversations.readWorkingState(threadId).hashCode();
        } catch (Exception ex) {
            return 0;
        }
    }
    private Map<String, Object> sanitizedArgs(Map<String, Object> args) {
        if (args == null) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        args.forEach((k, v) -> {
            String text = v == null ? "" : String.valueOf(v);
            sanitized.put(k, text.length() <= 500 ? v : text.substring(0, 500) + "\u2026");
        });
        return sanitized;
    }
    private String sanitizedReason(CapabilityResult result) {
        Object reason = result.content().get("reason");
        String text = reason == null ? "Tool execution failed" : String.valueOf(reason);
        return text.length() <= 500 ? text : text.substring(0, 500) + "\u2026";
    }
    private boolean isNotFound(CapabilityResult result) {
        return sanitizedReason(result).contains("not found") || sanitizedReason(result).contains("Not found");
    }
    private String statusLabelFor(String capabilityId) {
        return switch (capabilityId) {
            case "project.create" -> "Creating project";
            case "project.search" -> "Searching projects";
            case "project.list_recent" -> "Listing recent projects";
            case "project.get_summary" -> "Reading project summary";
            default -> "Working";
        };
    }
    private String toolSummary(String capabilityId, CapabilityResult result) {
        Object candidates = result.content().get("candidates");
        if (candidates instanceof List<?> list) {
            return "Found " + list.size() + " candidate(s)";
        }
        Object projects = result.content().get("projects");
        if (projects instanceof List<?> list) {
            return "Found " + list.size() + " recent project(s)";
        }
        Object title = result.content().get("title");
        if (title != null) {
            return String.valueOf(title);
        }
        return "Done";
    }
    private String defaultNavigationText(GlobalAssistantDecision.UiAction uiAction) {
        return switch (uiAction.destination()) {
            case PROJECT -> "Opening the project now.";
            case PROJECTS -> "Showing your projects now.";
            case SKILLS -> "Opening skills now.";
            case CONNECTIONS -> "Opening connections now.";
            case SETTINGS -> "Opening settings now.";
        };
    }
    @Transactional
    public void markRunning(UUID runId) {
        try {
            runs.markRunning(runId);
        } catch (IllegalStateException ignored) {
        }
    }
    @Transactional
    public void incrementStep(UUID runId) {
        runs.incrementStep(runId);
    }
    @Transactional
    public void appendEvent(UUID runId, String type, Map<String, Object> payload) {
        events.append(runId, type, payload);
        streams.publish(runId, events.findByRun(runId).get(events.findByRun(runId).size() - 1));
    }
    public boolean isCancelRequested(UUID runId) {
        return runs.findById(runId).map(r -> r.cancelRequestedAt() != null).orElse(false);
    }
    @Transactional
    public void persistAssistant(UUID threadId, UUID runId, String text) {
        conversations.appendAssistantMessage(threadId, text, runId);
    }
    @Transactional
    public void completeWithText(UUID threadId, UUID runId, String text, GlobalAssistantDecision.UiAction deferredUi) {
        persistAssistantInTx(threadId, runId, text);
        appendEventInTx(runId, GlobalAssistantEventType.ASSISTANT_DELTA, Map.of("text", text));
        appendEventInTx(runId, GlobalAssistantEventType.ASSISTANT_COMPLETED, Map.of());
        terminalizeCompletedInTx(threadId, runId);
    }
    @Transactional
    public void terminalizeCompleted(UUID threadId, UUID runId) {
        terminalizeCompletedInTx(threadId, runId);
    }
    private void persistAssistantInTx(UUID threadId, UUID runId, String text) {
        conversations.appendAssistantMessage(threadId, text, runId);
    }
    private void appendEventInTx(UUID runId, String type, Map<String, Object> payload) {
        var appended = events.append(runId, type, payload);
        streams.publish(runId, appended);
    }
    private void terminalizeCompletedInTx(UUID threadId, UUID runId) {
        appendEventInTx(runId, GlobalAssistantEventType.RUN_COMPLETED, Map.of());
        try {
            runs.terminalize(runId, GlobalAssistantRunStatus.COMPLETED, null);
        } catch (IllegalStateException ignored) {
        }
    }
    @Transactional
    public void terminalizeCancelled(UUID threadId, UUID runId) {
        appendEventInTx(runId, GlobalAssistantEventType.RUN_CANCELLED, Map.of());
        try {
            runs.terminalize(runId, GlobalAssistantRunStatus.CANCELLED, GlobalAssistantErrorCode.RUN_CANCELLED);
        } catch (IllegalStateException ignored) {
        }
    }
    @Transactional
    public void failRun(UUID threadId, UUID runId, String errorCode, String reason,
            List<Map<String, Object>> observations) {
        String text = "I couldn't complete that step (" + errorCode + ").";
        if (GlobalAssistantErrorCode.RUN_STEP_LIMIT.equals(errorCode)) {
            text = "That needed more steps than I can take in one go, so I stopped here.";
        } else if (GlobalAssistantErrorCode.PROJECT_NOT_FOUND.equals(errorCode)) {
            text = "I couldn't find that project.";
        }
        try {
            conversations.appendAssistantMessage(threadId, text, runId);
        } catch (Exception ignored) {
        }
        appendEventInTx(runId, GlobalAssistantEventType.ASSISTANT_DELTA, Map.of("text", text));
        appendEventInTx(runId, GlobalAssistantEventType.ASSISTANT_COMPLETED, Map.of());
        appendEventInTx(runId, GlobalAssistantEventType.RUN_FAILED,
                Map.of("errorCode", errorCode, "reason", truncate(reason)));
        try {
            runs.terminalize(runId, GlobalAssistantRunStatus.FAILED, errorCode);
        } catch (IllegalStateException ignored) {
        }
    }
    private void emitUiAction(UUID runId, GlobalAssistantDecision.UiAction uiAction) {
        if (uiAction.resourceId() != null && !uiAction.resourceId().isBlank()) {
            String trimmed = uiAction.resourceId().trim();
            if (trimmed.startsWith("http") || trimmed.contains("://") || trimmed.startsWith("javascript:")) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "UI resource must be a project id");
            }
            try {
                UUID resource = UUID.fromString(trimmed);
                appendEvent(runId, GlobalAssistantEventType.UI_ACTION, Map.of(
                        "destination", uiAction.destination().name(), "resourceId", resource.toString()));
                return;
            } catch (IllegalArgumentException ex) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "UI resource must be a project id");
            }
        }
        appendEvent(runId, GlobalAssistantEventType.UI_ACTION,
                Map.of("destination", uiAction.destination().name()));
    }
    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "\u2026";
    }
}
