package com.specagent.globalassistant.runtime;

import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.globalassistant.context.GlobalAssistantContext;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantEventType;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantVersionConflictException;
import com.specagent.globalassistant.conversation.GlobalAssistantWorkingState;
import com.specagent.globalassistant.model.GlobalAssistantBrain;
import com.specagent.globalassistant.model.GlobalAssistantDecision;
import com.specagent.globalassistant.model.GlobalAssistantModelException;
import com.specagent.globalassistant.stream.GlobalAssistantRunEventService;
import com.specagent.globalassistant.turn.RunTerminalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bounded sequential tool-agent loop. Owns one-run orchestration only:
 * decision -&gt; tool -&gt; observation -&gt; decision, plus cancel, budgets,
 * no-progress, persistence coordination and terminalization.
 * Steer handoff lives in turn package; this runtime only publishes
 * terminal events so backend-owned continuation stays decoupled.
 */
@Service
public class GlobalAssistantRuntime {
    private final GlobalAssistantConversationService conversations;
    private final GlobalAssistantContextBuilder contextBuilder;
    private final GlobalAssistantBrain brain;
    private final CapabilityRuntime capabilities;
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantToolArgumentCanonicalizer canonicalizer;
    private final GlobalAssistantRuntimeProperties budgets;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final GlobalAssistantRunEventService runEvents;
    private final GlobalAssistantUiActionValidator uiValidator;
    private final com.specagent.globalassistant.model.GlobalAssistantSummaryService summaries;
    private final ApplicationEventPublisher eventsPublisher;
    private static final Logger log = LoggerFactory.getLogger(GlobalAssistantRuntime.class);
    @Autowired
    public GlobalAssistantRuntime(GlobalAssistantConversationService conversations,
            GlobalAssistantContextBuilder contextBuilder, GlobalAssistantBrain brain,
            CapabilityRuntime capabilities, GlobalAssistantRunRepository runs,
            GlobalAssistantToolArgumentCanonicalizer canonicalizer,
            GlobalAssistantRuntimeProperties budgets, GlobalAssistantRunLifecycleService lifecycle,
            GlobalAssistantRunEventService runEvents, GlobalAssistantUiActionValidator uiValidator,
            com.specagent.globalassistant.model.GlobalAssistantSummaryService summaries,
            ApplicationEventPublisher eventsPublisher) {
        this.conversations = conversations;
        this.contextBuilder = contextBuilder;
        this.brain = brain;
        this.capabilities = capabilities;
        this.runs = runs;
        this.canonicalizer = canonicalizer;
        this.budgets = budgets;
        this.lifecycle = lifecycle;
        this.runEvents = runEvents;
        this.uiValidator = uiValidator;
        this.summaries = summaries;
        this.eventsPublisher = eventsPublisher;
    }
    /** Test-only path without event bus. */
    public GlobalAssistantRuntime(GlobalAssistantConversationService conversations,
            GlobalAssistantContextBuilder contextBuilder, GlobalAssistantBrain brain,
            CapabilityRuntime capabilities, GlobalAssistantRunRepository runs,
            GlobalAssistantToolArgumentCanonicalizer canonicalizer,
            GlobalAssistantRuntimeProperties budgets, GlobalAssistantRunLifecycleService lifecycle,
            GlobalAssistantRunEventService runEvents, GlobalAssistantUiActionValidator uiValidator,
            com.specagent.globalassistant.model.GlobalAssistantSummaryService summaries) {
        this(conversations, contextBuilder, brain, capabilities, runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries, null);
    }
    /**
     * Executes one user turn synchronously. The run row already exists;
     * this method drives it to a terminal state.
     */
    public void executeRun(UUID threadId, UUID runId, String userMessage,
            GlobalAssistantContextBuilder.UiRequest uiRequest) {
        try {
            lifecycle.claimAndStart(runId);
        } catch (GlobalAssistantRunClaimedException ex) {
            log.debug("Global assistant run already owned, skipping duplicate dispatch: runId={} status={}",
                    runId, ex.status());
            return;
        }
        List<Map<String, Object>> observations = new ArrayList<>();
        List<GlobalAssistantObservation> typedObservations = new ArrayList<>();
        String lastCapability = null;
        String lastCanonicalArgs = null;
        int observationFingerprint = 0;
        int toolCalls = 0;
        int steps = 0;
        while (true) {
            if (isCancelRequested(runId)) {
                cancelAndPublish(threadId, runId);
                return;
            }
            if (steps >= budgets.maxSteps()) {
                failRun(threadId, runId, GlobalAssistantErrorCode.RUN_STEP_LIMIT,
                        "Step budget exhausted", observations);
                return;
            }
            GlobalAssistantContext context;
            try {
                context = contextBuilder.build(threadId, runId, userMessage, uiRequest);
            } catch (IllegalStateException ex) {
                failRun(threadId, runId, GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                        "Working-state storage is corrupt", observations);
                return;
            }
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
            if (isCancelRequested(runId)) {
                cancelAndPublish(threadId, runId);
                return;
            }
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
                        finishSuccessfully(threadId, runId, userMessage,
                                "The same lookup was already tried without new results, so I stopped here.",
                                null, null);
                        return;
                    }
                }
                if (isCancelRequested(runId)) {
                    cancelAndPublish(threadId, runId);
                    return;
                }
                String statusLabel = statusLabelFor(decision.toolRequest().capabilityId());
                runEvents.append(runId, GlobalAssistantEventType.STATUS, Map.of("message", statusLabel));
                runEvents.append(runId, GlobalAssistantEventType.TOOL_STARTED, Map.of(
                        "capabilityId", decision.toolRequest().capabilityId(),
                        "arguments", sanitizedArgs(decision.toolRequest().arguments())));
                CapabilityResult result;
                try {
                    result = executeTool(runId, toolCalls, decision);
                } catch (RuntimeException ex) {
                    runEvents.append(runId, GlobalAssistantEventType.TOOL_FAILED, Map.of(
                            "capabilityId", decision.toolRequest().capabilityId(),
                            "errorCode", GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "reason", "Tool execution failed"));
                    failRun(threadId, runId, GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "Tool execution failed", observations);
                    return;
                }
                toolCalls++;
                GlobalAssistantObservation observation = toObservation(decision.toolRequest().capabilityId(), result);
                typedObservations.add(observation);
                observations.add(observation.toMap());
                updateWorkingState(threadId, decision.toolRequest().capabilityId(), result);
                if (result.status() == CapabilityResult.Status.SUCCEEDED
                        || result.status() == CapabilityResult.Status.REPLAYED) {
                    runEvents.append(runId, GlobalAssistantEventType.TOOL_COMPLETED, Map.of(
                            "capabilityId", decision.toolRequest().capabilityId(),
                            "summary", toolSummary(decision.toolRequest().capabilityId(), result)));
                } else if (result.status() == CapabilityResult.Status.IN_PROGRESS) {
                    runEvents.append(runId, GlobalAssistantEventType.TOOL_FAILED, Map.of(
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
                    runEvents.append(runId, GlobalAssistantEventType.TOOL_FAILED, Map.of(
                            "capabilityId", decision.toolRequest().capabilityId(),
                            "errorCode", errorCode,
                            "reason", sanitizedReason(result)));
                }
                lastCapability = decision.toolRequest().capabilityId();
                lastCanonicalArgs = canonicalArgs;
                observationFingerprint = observations.hashCode() + workingStateFingerprint(threadId);
                continue;
            }
            if (decision.requiresUserInput()) {
                if (isCancelRequested(runId)) {
                    cancelAndPublish(threadId, runId);
                    return;
                }
                String question = decision.assistantText();
                try {
                    rememberClarification(threadId, question);
                } catch (IllegalStateException ex) {
                    failRun(threadId, runId, GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "Working-state storage is corrupt", observations);
                    return;
                }
                if (isCancelRequested(runId)) {
                    cancelAndPublish(threadId, runId);
                    return;
                }
                lifecycle.completeForClarification(threadId, runId, question);
                publishTerminal(threadId, runId, "COMPLETED");
                refreshSummaryBestEffort(threadId, runId);
                return;
            }
            if (decision.toolRequest() == null && decision.uiAction() == null && !decision.done()) {
                failRun(threadId, runId, GlobalAssistantErrorCode.MODEL_INVALID_RESPONSE,
                        "Indecisive model response", observations);
                return;
            }
            if (isCancelRequested(runId)) {
                cancelAndPublish(threadId, runId);
                return;
            }
            String text = decision.assistantText() != null ? decision.assistantText() : "";
            String uiDestination = null;
            String uiResourceId = null;
            if (decision.uiAction() != null) {
                UUID validated;
                try {
                    validated = decision.uiAction().resourceId() == null
                            || decision.uiAction().resourceId().isBlank() ? null
                            : uiValidator.requireExistingProject(decision.uiAction().resourceId());
                } catch (GlobalAssistantModelException ex) {
                    failRun(threadId, runId, ex.errorCode(), ex.getMessage(), observations);
                    return;
                }
                if (isCancelRequested(runId)) {
                    cancelAndPublish(threadId, runId);
                    return;
                }
                uiDestination = decision.uiAction().destination().name();
                uiResourceId = validated == null ? null : validated.toString();
                if (text.isBlank()) {
                    text = defaultNavigationText(decision.uiAction());
                }
            }
            if (isCancelRequested(runId)) {
                cancelAndPublish(threadId, runId);
                return;
            }
            finishSuccessfully(threadId, runId, userMessage, text, uiDestination, uiResourceId);
            return;
        }
    }
    private CapabilityResult executeTool(UUID runId, int toolIndex, GlobalAssistantDecision decision) {
        String invocationKey = "ga:" + runId + ":tool:" + toolIndex;
        return capabilities.invokeApplicationScoped(invocationKey,
                decision.toolRequest().capabilityId(), runId, decision.toolRequest().arguments());
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
        String code = structuredErrorCode(result);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        return new GlobalAssistantObservation(capabilityId, false, data, code);
    }
    private String structuredErrorCode(CapabilityResult result) {
        Object code = result.content().get("errorCode");
        if (code instanceof String text
                && (GlobalAssistantErrorCode.PROJECT_NOT_FOUND.equals(text)
                        || GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID.equals(text)
                        || GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED.equals(text))) {
            return text;
        }
        return GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED;
    }
    public void updateWorkingState(UUID threadId, String capabilityId, CapabilityResult result) {
        updateWorkingStateRetrying(threadId,
                current -> evolveWorkingState(current, capabilityId, result));
    }
    private void updateWorkingStateRetrying(UUID threadId,
            java.util.function.UnaryOperator<GlobalAssistantWorkingState> evolve) {
        for (int attempt = 0; attempt < 2; attempt++) {
            GlobalAssistantWorkingState current = conversations.readWorkingState(threadId);
            var thread = conversations.findThread(threadId).orElseThrow();
            try {
                conversations.writeWorkingState(threadId, evolve.apply(current), thread.workingStateVersion());
                return;
            } catch (GlobalAssistantVersionConflictException ex) {
                if (attempt == 0) {
                    continue;
                }
                throw ex;
            }
        }
    }
    private void rememberClarification(UUID threadId, String question) {
        String bounded = question.length() <= 500 ? question : question.substring(0, 500);
        updateWorkingStateRetrying(threadId, current -> new GlobalAssistantWorkingState(
                current.goal(), current.candidateProjects(), bounded,
                current.lastResolvedProjectId(), current.lastToolResultRefs()));
    }
    private void finishSuccessfully(UUID threadId, UUID runId, String userMessage, String text,
            String uiDestination, String uiResourceId) {
        settleWorkingStateOnCompletion(threadId, userMessage);
        if (uiDestination != null) {
            lifecycle.completeWithAssistantAndUiAction(threadId, runId, text, uiDestination, uiResourceId);
        } else {
            lifecycle.completeWithAssistant(threadId, runId, text);
        }
        publishTerminal(threadId, runId, "COMPLETED");
        refreshSummaryBestEffort(threadId, runId);
    }
    private void refreshSummaryBestEffort(UUID threadId, UUID runId) {
        try {
            summaries.maybeSummarize(threadId, runId);
        } catch (RuntimeException ex) {
            log.warn("Global assistant summary refresh failed: runId={} error={}",
                    runId, ex.getClass().getSimpleName());
        }
    }
    private void settleWorkingStateOnCompletion(UUID threadId, String userMessage) {
        updateWorkingStateRetrying(threadId, current -> {
            String goal = current.waitingFor() != null ? current.goal() : boundedGoal(userMessage, current.goal());
            return new GlobalAssistantWorkingState(goal, java.util.List.of(), null,
                    current.lastResolvedProjectId(), current.lastToolResultRefs());
        });
    }
    private String boundedGoal(String userMessage, String fallback) {
        if (userMessage == null || userMessage.isBlank()) {
            return fallback;
        }
        String trimmed = userMessage.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
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
        return conversations.readWorkingState(threadId).hashCode();
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
        return GlobalAssistantErrorCode.PROJECT_NOT_FOUND.equals(structuredErrorCode(result));
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
    public void incrementStep(UUID runId) {
        runs.incrementStep(runId);
    }
    public boolean isCancelRequested(UUID runId) {
        return runs.findById(runId).map(r -> r.cancelRequestedAt() != null).orElse(false);
    }
    private void failRun(UUID threadId, UUID runId, String errorCode, String reason,
            List<Map<String, Object>> observations) {
        String text = "I couldn't complete that step (" + errorCode + ").";
        if (GlobalAssistantErrorCode.RUN_STEP_LIMIT.equals(errorCode)) {
            text = "That needed more steps than I can take in one go, so I stopped here.";
        } else if (GlobalAssistantErrorCode.PROJECT_NOT_FOUND.equals(errorCode)) {
            text = "I couldn't find that project.";
        }
        lifecycle.failWithAssistant(threadId, runId, text, errorCode, truncate(reason));
        publishTerminal(threadId, runId, "FAILED");
    }
    private void cancelAndPublish(UUID threadId, UUID runId) {
        try {
            lifecycle.cancelAndTerminalize(runId);
        } finally {
            publishTerminal(threadId, runId, "CANCELLED");
        }
    }
    private void publishTerminal(UUID threadId, UUID runId, String status) {
        if (eventsPublisher == null) {
            return;
        }
        try {
            eventsPublisher.publishEvent(new RunTerminalEvent(threadId, runId, status));
        } catch (Exception ex) {
            log.debug("Global assistant terminal event publish failed: runId={}", runId);
        }
    }
    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "\u2026";
    }
}
