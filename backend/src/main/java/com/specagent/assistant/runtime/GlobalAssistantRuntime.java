package com.specagent.assistant.runtime;

import com.specagent.assistant.runtime.GlobalAssistantSummaryService;

import com.specagent.assistant.GlobalAssistantErrorCode;

import com.specagent.assistant.runtime.GlobalAssistantRuntime;

import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.assistant.model.GlobalAssistantContext;
import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantEventType;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantVersionConflictException;
import com.specagent.assistant.conversation.GlobalAssistantWorkingState;
import com.specagent.assistant.model.GlobalAssistantBrain;
import com.specagent.assistant.model.GlobalAssistantDecision;
import com.specagent.assistant.model.GlobalAssistantModelException;
import com.specagent.assistant.runtime.GlobalAssistantRunEventService;
import com.specagent.assistant.tool.SkillDiscoverCapability;
import com.specagent.assistant.tool.SkillImportCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final GlobalAssistantSummaryService summaries;
    private final com.specagent.assistant.model.GlobalAssistantModelTargetResolver modelTargets;
    private static final Logger log = LoggerFactory.getLogger(GlobalAssistantRuntime.class);
    public GlobalAssistantRuntime(GlobalAssistantConversationService conversations,
            GlobalAssistantContextBuilder contextBuilder, GlobalAssistantBrain brain,
            CapabilityRuntime capabilities, GlobalAssistantRunRepository runs,
            GlobalAssistantToolArgumentCanonicalizer canonicalizer,
            GlobalAssistantRuntimeProperties budgets, GlobalAssistantRunLifecycleService lifecycle,
            GlobalAssistantRunEventService runEvents, GlobalAssistantUiActionValidator uiValidator,
            GlobalAssistantSummaryService summaries,
            com.specagent.assistant.model.GlobalAssistantModelTargetResolver modelTargets) {
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
        this.modelTargets = modelTargets;
    }
    /**
     * Executes one user turn synchronously. The run row already exists;
     * this method drives it to a terminal state.
     */
    public void executeRun(UUID threadId, UUID runId, String userMessage,
            GlobalAssistantContextBuilder.UiRequest uiRequest) {
        long runStartNanos = System.nanoTime();
        long queueMs = 0;
        long contextBuildMs = 0;
        long decision1Ms = 0;
        long toolExecutionMs = 0;
        long decision2Ms = 0;
        long repairMs = 0;
        long summaryMs = 0;
        int decisionCount = 0;
        AnswerStreamPublisher streamer = new AnswerStreamPublisher(runEvents, runId, runStartNanos);
        try {
            long queueStart = System.nanoTime();
            try {
                lifecycle.claimAndStart(runId);
            } catch (GlobalAssistantRunClaimedException ex) {
                log.debug("Global assistant run already owned, skipping duplicate dispatch: runId={} status={}",
                        runId, ex.status());
                return;
            } finally {
                queueMs = (System.nanoTime() - queueStart) / 1_000_000;
            }
        // Snapshot the provider/model actually serving THIS run's inference
        // requests, taken once at request time. Messages persist this exact
        // attribution, so later provider switches never mislabel old answers.
        var attribution = modelTargets.resolveActive();
        var attributionProvider = attribution.providerLabel();
        var attributionModel = attribution.modelId();
        List<Map<String, Object>> observations = new ArrayList<>();
        List<GlobalAssistantObservation> typedObservations = new ArrayList<>();
        String lastCapability = null;
        String lastCanonicalArgs = null;
        int observationFingerprint = 0;
        int toolCalls = 0;
        int steps = 0;
        while (true) {
            if (isCancelRequested(runId)) {
                lifecycle.cancelAndTerminalize(runId);
                return;
            }
            if (steps >= budgets.maxSteps()) {
                failRun(threadId, runId, GlobalAssistantErrorCode.RUN_STEP_LIMIT,
                        "Step budget exhausted", observations, attributionProvider, attributionModel);
                return;
            }
            GlobalAssistantContext context;
            try {
                long t0 = System.nanoTime();
                try {
                    context = contextBuilder.build(threadId, runId, userMessage, uiRequest);
                } finally {
                    contextBuildMs += (System.nanoTime() - t0) / 1_000_000;
                }
            } catch (IllegalStateException ex) {
                failRun(threadId, runId, GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                        "Working-state storage is corrupt", observations, attributionProvider, attributionModel);
                return;
            }
            GlobalAssistantDecision decision;
            try {
                long t0 = System.nanoTime();
                try {
                    streamer.nextGeneration();
                    decision = brain.decideStreaming(runId, context, observations,
                            () -> !isCancelRequested(runId), fragment -> {
                                streamer.accept(fragment);
                                return true;
                            });
                    streamer.finish();
                } finally {
                    long d = (System.nanoTime() - t0) / 1_000_000;
                    decisionCount++;
                    if (decisionCount == 1) decision1Ms += d;
                    else decision2Ms += d;
                }
            } catch (com.specagent.model.contract.StreamCancelledException ex) {
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                failRun(threadId, runId, GlobalAssistantErrorCode.MODEL_UNAVAILABLE,
                        "Model stream interrupted", observations, attributionProvider, attributionModel);
                return;
            } catch (GlobalAssistantModelException ex) {
                if (!GlobalAssistantErrorCode.MODEL_INVALID_RESPONSE.equals(ex.errorCode())) {
                    failRun(threadId, runId, ex.errorCode(), ex.getMessage(), observations, attributionProvider, attributionModel);
                    return;
                }
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                try {
                    long t0 = System.nanoTime();
                    try {
                        streamer.nextGeneration();
                        decision = brain.repairDecisionStreaming(runId, context, observations,
                                sanitizedRejectionReason(ex.getMessage()), () -> !isCancelRequested(runId),
                                fragment -> {
                                    streamer.accept(fragment);
                                    return true;
                                });
                        streamer.finish();
                    } finally {
                        repairMs += (System.nanoTime() - t0) / 1_000_000;
                    }
                } catch (com.specagent.model.contract.StreamCancelledException repairCancel) {
                    if (isCancelRequested(runId)) {
                        lifecycle.cancelAndTerminalize(runId);
                        return;
                    }
                    failRun(threadId, runId, GlobalAssistantErrorCode.MODEL_UNAVAILABLE,
                            "Model stream interrupted", observations, attributionProvider, attributionModel);
                    return;
                } catch (GlobalAssistantModelException repairEx) {
                    failRun(threadId, runId, repairEx.errorCode(), repairEx.getMessage(), observations, attributionProvider, attributionModel);
                    return;
                } catch (RuntimeException repairEx) {
                    failRun(threadId, runId, GlobalAssistantErrorCode.MODEL_UNAVAILABLE,
                            "Model unavailable", observations, attributionProvider, attributionModel);
                    return;
                }
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
            } catch (RuntimeException ex) {
                failRun(threadId, runId, GlobalAssistantErrorCode.MODEL_UNAVAILABLE,
                        "Model unavailable", observations, attributionProvider, attributionModel);
                return;
            }
            incrementStep(runId);
            steps++;
            if (isCancelRequested(runId)) {
                lifecycle.cancelAndTerminalize(runId);
                return;
            }
            if (decision.kind() == null) {
                failRun(threadId, runId, GlobalAssistantErrorCode.MODEL_INVALID_RESPONSE,
                        "Missing decision kind", observations, attributionProvider, attributionModel);
                return;
            }
            if (decision.kind() == GlobalAssistantDecision.DecisionKind.TOOL) {
                if (toolCalls >= budgets.maxToolCalls()) {
                    failRun(threadId, runId, GlobalAssistantErrorCode.RUN_STEP_LIMIT,
                            "Tool budget exhausted", observations, attributionProvider, attributionModel);
                    return;
                }
                String canonicalArgs = canonicalizer.canonicalize(decision.toolRequest().arguments());
                if (lastCapability != null && lastCapability.equals(decision.toolRequest().capabilityId())
                        && lastCanonicalArgs != null && lastCanonicalArgs.equals(canonicalArgs)) {
                    int currentFingerprint = observations.hashCode() + workingStateFingerprint(threadId);
                    if (currentFingerprint == observationFingerprint) {
                        summaryMs += finishSuccessfully(threadId, runId, userMessage,
                                "The same lookup was already tried without new results, so I stopped here.",
                                null, null, attributionProvider, attributionModel);
                        return;
                    }
                }
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                String statusLabel = statusLabelFor(decision.toolRequest().capabilityId());
                runEvents.append(runId, GlobalAssistantEventType.STATUS, Map.of("message", statusLabel));
                runEvents.append(runId, GlobalAssistantEventType.TOOL_STARTED, Map.of(
                        "capabilityId", decision.toolRequest().capabilityId(),
                        "arguments", sanitizedArgs(decision.toolRequest().arguments())));
                CapabilityResult result;
                try {
                    long t0 = System.nanoTime();
                    try {
                        result = executeTool(runId, toolCalls, decision);
                    } finally {
                        toolExecutionMs += (System.nanoTime() - t0) / 1_000_000;
                    }
                } catch (RuntimeException ex) {
                    // Never swallow silently: the TOOL_FAILED copy is generic,
                    // so the log is the only place the real cause survives.
                    log.warn("GA tool execution failed: runId={} capabilityId={} error={}",
                            runId, decision.toolRequest().capabilityId(),
                            ex.getClass().getSimpleName(), ex);
                    runEvents.append(runId, GlobalAssistantEventType.TOOL_FAILED, Map.of(
                            "capabilityId", decision.toolRequest().capabilityId(),
                            "errorCode", GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "reason", "Tool execution failed"));
                    failRun(threadId, runId, GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "Tool execution failed", observations, attributionProvider, attributionModel);
                    return;
                }
                toolCalls++;
                GlobalAssistantObservation observation = toObservation(decision.toolRequest().capabilityId(), result);
                typedObservations.add(observation);
                observations.add(observation.toMap());
                updateWorkingState(threadId, decision.toolRequest().capabilityId(), result);
                if (result.status() == CapabilityResult.Status.SUCCEEDED
                        || result.status() == CapabilityResult.Status.REPLAYED) {
                    java.util.List<Map<String, Object>> refs =
                            com.specagent.assistant.tool.GlobalAssistantToolPresentation
                                    .projectResources(decision.toolRequest().capabilityId(), result.content());
                    Map<String, Object> completedPayload = new LinkedHashMap<>();
                    completedPayload.put("capabilityId", decision.toolRequest().capabilityId());
                    completedPayload.put("summary", toolSummary(decision.toolRequest().capabilityId(), result));
                    completedPayload.put("resourceRefs", refs);
                    completedPayload.put("resultCount", refs.size());
                    // resultKind is null for capabilities without a list/kind
                    // mapping (skill.import): absence of the key is the honest
                    // signal. A null value here would kill the whole run —
                    // Map.copyOf in the event repository rejects null values.
                    String resultKind = com.specagent.assistant.tool.GlobalAssistantToolPresentation
                            .resultKind(decision.toolRequest().capabilityId());
                    if (resultKind != null) {
                        completedPayload.put("resultKind", resultKind);
                    }
                    runEvents.append(runId, GlobalAssistantEventType.TOOL_COMPLETED, completedPayload);
                    // Real phase transition, not a timer: the tool finished and the
                    // final model round starts now. Keeps the UI truthful during
                    // the second model call without narrating tool internals.
                    runEvents.append(runId, GlobalAssistantEventType.STATUS, Map.of("message",
                            com.specagent.assistant.tool.GlobalAssistantToolPresentation.COMPOSING_MESSAGE));
                } else if (result.status() == CapabilityResult.Status.IN_PROGRESS) {
                    runEvents.append(runId, GlobalAssistantEventType.TOOL_FAILED, Map.of(
                            "capabilityId", decision.toolRequest().capabilityId(),
                            "errorCode", GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "reason", "Tool is still running; stopping the loop honestly."));
                    failRun(threadId, runId, GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "Tool still in progress", observations, attributionProvider, attributionModel);
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
            if (decision.kind() == GlobalAssistantDecision.DecisionKind.CLARIFY) {
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                String question = decision.assistantText();
                try {
                    rememberClarification(threadId, question);
                } catch (IllegalStateException ex) {
                    failRun(threadId, runId, GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "Working-state storage is corrupt", observations, attributionProvider, attributionModel);
                    return;
                }
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                lifecycle.completeForClarification(threadId, runId, question,
                        attributionProvider, attributionModel);
                refreshSummaryBestEffort(threadId, runId);
                return;
            }
            if (decision.kind() == GlobalAssistantDecision.DecisionKind.NAVIGATE) {
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                String text = decision.assistantText();
                if (text == null || text.isBlank()) {
                    text = defaultNavigationText(decision.uiAction());
                }
                UUID validated;
                try {
                    validated = decision.uiAction() == null
                            || decision.uiAction().resourceId() == null
                            || decision.uiAction().resourceId().isBlank() ? null
                            : uiValidator.requireExistingProject(decision.uiAction().resourceId());
                } catch (GlobalAssistantModelException ex) {
                    failRun(threadId, runId, ex.errorCode(), ex.getMessage(), observations, attributionProvider, attributionModel);
                    return;
                }
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                String uiDestination = decision.uiAction().destination().name();
                String uiResourceId = validated == null ? null : validated.toString();
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                summaryMs += finishSuccessfully(threadId, runId, userMessage, text, uiDestination, uiResourceId,
                        attributionProvider, attributionModel);
                return;
            }
            if (decision.kind() == GlobalAssistantDecision.DecisionKind.FINAL) {
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                String text = decision.assistantText() != null ? decision.assistantText() : "";
                if (isCancelRequested(runId)) {
                    lifecycle.cancelAndTerminalize(runId);
                    return;
                }
                summaryMs += finishSuccessfully(threadId, runId, userMessage, text, null, null,
                        attributionProvider, attributionModel);
                return;
            }
            failRun(threadId, runId, GlobalAssistantErrorCode.MODEL_INVALID_RESPONSE,
                    "Unknown decision kind", observations, attributionProvider, attributionModel);
            return;
        }
        } finally {
            long runTotalMs = (System.nanoTime() - runStartNanos) / 1_000_000;
            long providerTotalMs = decision1Ms + decision2Ms + repairMs + summaryMs;
            log.info("GA timing runId={} threadId={} queue_ms={} context_build_ms={} model_decision_1_ms={} tool_execution_ms={} model_decision_2_ms={} repair_ms={} summary_ms={} provider_total_ms={} run_total_ms={}",
                    runId, threadId, queueMs, contextBuildMs, decision1Ms, toolExecutionMs, decision2Ms, repairMs, summaryMs, providerTotalMs, runTotalMs);
            if (streamer.generation() > 0) {
                log.info("GA stream runId={} threadId={} generations={} deltas={} first_delta_ms={}",
                        runId, threadId, streamer.generation(), streamer.deltaCount(),
                        streamer.firstDeltaMillisSinceRunStart());
            }
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
                current.lastResolvedProjectId(), current.lastToolResultRefs(),
                current.lastSkillDiscovery()));
    }
    private long finishSuccessfully(UUID threadId, UUID runId, String userMessage, String text,
            String uiDestination, String uiResourceId, String providerLabel, String modelId) {
        settleWorkingStateOnCompletion(threadId, userMessage);
        if (uiDestination != null) {
            lifecycle.completeWithAssistantAndUiAction(threadId, runId, text, uiDestination, uiResourceId,
                    providerLabel, modelId);
        } else {
            lifecycle.completeWithAssistant(threadId, runId, text, providerLabel, modelId);
        }
        return refreshSummaryBestEffort(threadId, runId);
    }
    private long refreshSummaryBestEffort(UUID threadId, UUID runId) {
        long t0 = System.nanoTime();
        try {
            summaries.maybeSummarize(threadId, runId);
        } catch (RuntimeException ex) {
            log.warn("Global assistant summary refresh failed: runId={} error={}",
                    runId, ex.getClass().getSimpleName());
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }
    private void settleWorkingStateOnCompletion(UUID threadId, String userMessage) {
        updateWorkingStateRetrying(threadId, current -> {
            String goal = current.waitingFor() != null ? current.goal() : boundedGoal(userMessage, current.goal());
            return new GlobalAssistantWorkingState(goal, java.util.List.of(), null,
                    current.lastResolvedProjectId(), current.lastToolResultRefs(),
                    current.lastSkillDiscovery());
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
        // Skill repository discovery is not project-shaped: its candidate list
        // is persisted verbatim as continuity state so the next turn can still
        // see which skills the tool actually returned.
        if (SkillDiscoverCapability.CAPABILITY_ID.equals(capabilityId)) {
            List<String> discoverRefs = new ArrayList<>(current.lastToolResultRefs());
            discoverRefs.add(capabilityId + ":" + content.get("candidateCount"));
            if (discoverRefs.size() > 20) {
                discoverRefs = discoverRefs.subList(discoverRefs.size() - 20, discoverRefs.size());
            }
            return new GlobalAssistantWorkingState(current.goal(), current.candidateProjects(),
                    current.waitingFor(), current.lastResolvedProjectId(), discoverRefs,
                    skillDiscoveryFrom(content));
        }
        List<Map<String, String>> candidates = new ArrayList<>(current.candidateProjects());
        UUID resolved = current.lastResolvedProjectId();
        List<String> refs = new ArrayList<>(current.lastToolResultRefs());
        // Capability result shapes live in the presentation registry; the
        // runtime only passes the opaque capability id through. A fifth
        // capability needs registration only, no runtime change.
        com.specagent.assistant.tool.GlobalAssistantToolPresentation.ResultShape shape =
                com.specagent.assistant.tool.GlobalAssistantToolPresentation.resultShapeOf(capabilityId);
        if (shape == null) {
            return current;
        }
        if (shape.listKey() != null) {
            candidates = new ArrayList<>(com.specagent.assistant.tool.GlobalAssistantToolPresentation
                    .candidatePairs(capabilityId, content));
            if (candidates.size() == 1) {
                try {
                    resolved = UUID.fromString(candidates.get(0).get("projectId"));
                } catch (IllegalArgumentException ignored) {
                }
            }
            refs.add(capabilityId + ":" + candidates.size());
        } else {
            String id = com.specagent.assistant.tool.GlobalAssistantToolPresentation
                    .directProjectId(capabilityId, content);
            if (id != null) {
                try {
                    resolved = UUID.fromString(id);
                } catch (IllegalArgumentException ignored) {
                }
            }
            refs.add(capabilityId + ":" + id);
        }
        if (refs.size() > 20) {
            refs = refs.subList(refs.size() - 20, refs.size());
        }
        return new GlobalAssistantWorkingState(current.goal(), candidates, current.waitingFor(),
                resolved, refs, current.lastSkillDiscovery());
    }
    /**
     * Projects a successful skill.import.discover result into bounded
     * continuity state: only parseable candidates survive, bounded to the
     * same 20-candidate cap the capability reports.
     */
    private GlobalAssistantWorkingState.SkillDiscovery skillDiscoveryFrom(Map<String, Object> content) {
        List<Map<String, String>> candidates = new ArrayList<>();
        Object raw = content.get("candidates");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                if (!Boolean.TRUE.equals(m.get("parseable"))) {
                    continue;
                }
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("path", String.valueOf(m.get("path")));
                entry.put("name", String.valueOf(m.get("name")));
                candidates.add(entry);
                if (candidates.size() >= 20) {
                    break;
                }
            }
        }
        String url = content.get("url") instanceof String s ? s : null;
        String ref = content.get("ref") instanceof String s ? s : null;
        String suggested = content.get("suggestedPath") instanceof String s ? s : null;
        if (url == null) {
            return null;
        }
        return new GlobalAssistantWorkingState.SkillDiscovery(url, ref, suggested, candidates);
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
        return com.specagent.assistant.tool.GlobalAssistantToolPresentation.runningMessage(capabilityId);
    }
    private String toolSummary(String capabilityId, CapabilityResult result) {
        // Skill import result shapes live here: requiresChoice means the model
        // must ask the user to pick one, stagedImportId means one package was
        // staged for review. Nothing about the import is executed or installed.
        if (SkillDiscoverCapability.CAPABILITY_ID.equals(capabilityId)) {
            Object candidateCount = result.content().get("candidateCount");
            return "仓库中共发现 " + candidateCount + " 个 Skill 候选";
        }
        if (SkillImportCapability.CAPABILITY_ID.equals(capabilityId)) {
            Object requiresChoice = result.content().get("requiresChoice");
            if (Boolean.TRUE.equals(requiresChoice)) {
                Object candidateCount = result.content().get("candidateCount");
                return "仓库中发现 " + candidateCount + " 个 Skill，等待选择";
            }
            if (result.content().containsKey("stagedImportId")) {
                return "Skill 已暂存，待你在 Skills 页面安装";
            }
            return "已完成";
        }
        Object candidates = result.content().get("candidates");
        if (candidates instanceof List<?> list) {
            return "找到 " + list.size() + " 个候选项目";
        }
        Object projects = result.content().get("projects");
        if (projects instanceof List<?> list) {
            return "找到 " + list.size() + " 个最近项目";
        }
        Object title = result.content().get("title");
        if (title != null) {
            return String.valueOf(title);
        }
        return "已完成";
    }
    /**
     * Fallback answer for a navigation-only decision (the model produced no
     * text of its own). Product copy lives in the UI language like every other
     * assistant-facing string, never in the wire language.
     */
    private String defaultNavigationText(GlobalAssistantDecision.UiAction uiAction) {
        return switch (uiAction.destination()) {
            case PROJECT -> "正在为你打开该项目…";
            case PROJECTS -> "正在打开项目列表…";
            case SKILLS -> "正在打开 Skills 设置页…";
            case CONNECTIONS -> "正在打开连接设置页…";
            case SETTINGS -> "正在打开设置页…";
        };
    }
    public void incrementStep(UUID runId) {
        runs.incrementStep(runId);
    }
    public boolean isCancelRequested(UUID runId) {
        return runs.findById(runId).map(r -> r.cancelRequestedAt() != null).orElse(false);
    }
    /**
     * Bounded sanitized contract error for repair prompts. Never carries raw
     * payloads, credentials, or stack traces; the brain only forwards these
     * short validator/parser messages.
     */
    private String sanitizedRejectionReason(String value) {
        if (value == null || value.isBlank()) {
            return "rejected decision";
        }
        String collapsed = value.trim().replaceAll("\\s+", " ");
        return collapsed.length() <= 300 ? collapsed : collapsed.substring(0, 300);
    }
    private void failRun(UUID threadId, UUID runId, String errorCode, String reason,
            List<Map<String, Object>> observations, String providerLabel, String modelId) {
        String text = "I couldn't complete that step (" + errorCode + ").";
        if (GlobalAssistantErrorCode.RUN_STEP_LIMIT.equals(errorCode)) {
            text = "That needed more steps than I can take in one go, so I stopped here.";
        } else if (GlobalAssistantErrorCode.PROJECT_NOT_FOUND.equals(errorCode)) {
            text = "I couldn't find that project.";
        }
        lifecycle.failWithAssistant(threadId, runId, text, errorCode, truncate(reason),
                providerLabel, modelId);
    }
    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "\u2026";
    }
}
