package com.specagent.eval;

import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.ObservationView;
import com.specagent.agent.contract.ProposedClaim;
import com.specagent.agent.contract.StateUpdateResult;
import com.specagent.agent.contract.UsageView;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.AgentDecisionEngine;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * B-fast scripted brain: replaces the production model at the structured
 * Brain-output boundary. Java canonical runtime (graph, validation, policy,
 * execution) is untouched — only {@code STATE_UPDATE}/{@code DECISION}
 * structured outputs are scripted from the scenario contract.
 *
 * <p>Registered as {@code @Primary} in eval tests so the production answer
 * cycle drives it through the real {@code AgentDecisionEngine} port.
 */
public class ScriptedBrain implements AgentDecisionEngine, BrainScriptInstaller {

    /** Active script installed before each attempt. */
    public record ActiveScript(
            BrainScript brainScript,
            String scenarioId,
            long seed,
            int paraphraseIndex,
            int failStateUpdateAt,
            int failDecisionAt,
            boolean malformedDecision) {
    }

    private final AtomicReference<ActiveScript> active = new AtomicReference<>();
    private final List<String> stateUpdateStages = new ArrayList<>();
    private final List<String> decisionStages = new ArrayList<>();
    private final List<String> requestPayloads = new ArrayList<>();
    private int providerRetries;

    /** Test wiring: exposes this brain as the primary decision engine. */
    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public ScriptedBrain scriptedBrain() {
            return new ScriptedBrain();
        }
    }

    @Override
    public void installScript(BrainScript brainScript, String scenarioId,
                              long seed, int paraphraseIndex) {
        install(brainScript, scenarioId, seed, paraphraseIndex);
    }

    @Override
    public void resetScripts() {
        reset();
    }

    public void install(BrainScript brainScript, String scenarioId, long seed,
                        int paraphraseIndex) {
        install(new ActiveScript(brainScript, scenarioId, seed, paraphraseIndex, -1, -1, false));
    }

    public void install(ActiveScript script) {
        reset();
        active.set(script);
    }

    public void reset() {
        active.set(null);
        stateUpdateStages.clear();
        decisionStages.clear();
        requestPayloads.clear();
        providerRetries = 0;
    }

    public void recordProviderRetry() {
        providerRetries++;
    }

    @Override
    public int providerRetries() {
        return providerRetries;
    }

    @Override
    public List<String> observedStages() {
        List<String> stages = new ArrayList<>(stateUpdateStages);
        stages.addAll(decisionStages);
        return stages;
    }

    public int stateUpdateCalls() {
        return stateUpdateStages.size();
    }

    public int decisionCalls() {
        return decisionStages.size();
    }

    public List<String> requestPayloads() {
        return List.copyOf(requestPayloads);
    }

    @Override
    public AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request) {
        ActiveScript script = requireScript();
        requestPayloads.add(AgentContracts.write(request));
        stateUpdateStages.add("STATE_UPDATE");
        if (stateUpdateStages.size() == script.failStateUpdateAt()) {
            throw new AgentBrainUnavailableException("scripted STATE_UPDATE failure",
                    new IllegalStateException("eval scripted provider failure"));
        }
        List<ProposedClaim> claims = new ArrayList<>();
        for (BrainClaim claim : script.brainScript().claims()) {
            claims.add(new ProposedClaim(
                    claim.kind(),
                    GraphStep.renderText(script.scenarioId(), claim.textSeed(),
                            script.paraphraseIndex(), Map.of()),
                    claim.status(),
                    claim.confidence(),
                    List.of()));
        }
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION,
                request.runId(),
                new StateUpdateResult(claims),
                null,
                null,
                new UsageView(1, List.of()),
                Map.of());
    }

    @Override
    public AgentResponseEnvelope runDecision(AgentRequestEnvelope request) {
        ActiveScript script = requireScript();
        requestPayloads.add(AgentContracts.write(request));
        decisionStages.add("DECISION");
        if (decisionStages.size() == script.failDecisionAt()) {
            throw new AgentBrainUnavailableException("scripted DECISION failure",
                    new IllegalStateException("eval scripted provider failure"));
        }
        if (script.malformedDecision()) {
            return new AgentResponseEnvelope(
                    AgentProtocol.DECISION_PROTOCOL_VERSION,
                    request.runId(),
                    null,
                    new ObservationView(List.of("x"), List.of(), List.of(), List.of()),
                    new ActionProposal(
                            "INVENTED_FAMILY",
                            Map.of(),
                            UUID.fromString(request.snapshot().snapshotId()),
                            request.snapshot().contextHash(),
                            List.of(),
                            UUID.randomUUID(),
                            request.runId().toString(),
                            List.of()),
                    new UsageView(1, List.of()),
                    Map.of());
        }
        BrainDecision decision = script.brainScript().decision();
        Map<String, Object> payload = renderPayload(
                decision.actionFamily(), decision.payload(), script, request);
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        List<String> anchorRefs = anchorRefsFor(decision.actionFamily(), request);
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION,
                request.runId(),
                null,
                new ObservationView(
                        renderList(script.brainScript().knownObservations(), script),
                        List.of(),
                        renderList(script.brainScript().conflictObservations(), script),
                        List.of()),
                new ActionProposal(
                        decision.actionFamily(),
                        payload,
                        snapshotId,
                        request.snapshot().contextHash(),
                        List.of(),
                        UUID.randomUUID(),
                        request.runId().toString() + ":" + decision.actionFamily(),
                        anchorRefs),
                new UsageView(1, List.of()),
                Map.of());
    }

    @Override
    public com.specagent.agent.contract.AgentArtifactResponse runArtifactGeneration(
            AgentRequestEnvelope request) {
        throw new UnsupportedOperationException("eval brain does not script artifact generation");
    }

    private ActiveScript requireScript() {
        ActiveScript script = active.get();
        if (script == null) {
            throw new IllegalStateException("ScriptedBrain has no active eval script");
        }
        return script;
    }

    private List<String> renderList(List<String> seeds, ActiveScript script) {
        List<String> rendered = new ArrayList<>();
        for (String seed : seeds) {
            rendered.add(GraphStep.renderText(
                    script.scenarioId(), seed, script.paraphraseIndex(), Map.of()));
        }
        if (rendered.isEmpty()) {
            rendered.add("eval observation for " + script.scenarioId());
        }
        return rendered;
    }

    /**
     * Renders the scenario-declared payload against the live request
     * snapshot: {@code node:} refs that name a step (e.g. {@code step:tip})
     * resolve to the snapshot's anchor; literal snapshot refs pass through
     * so the Java validator stays authoritative.
     */
    private Map<String, Object> renderPayload(String family, Map<String, Object> declared,
                                              ActiveScript script, AgentRequestEnvelope request) {
        Map<String, Object> payload = new LinkedHashMap<>(declared);
        UUID anchor = request.snapshot().anchorNodeId();
        String anchorRef = anchor == null ? null : "node:" + anchor;
        switch (family) {
            case "REQUEST_USER_INPUT", "CREATE_NODE" -> {
                if (payload.containsKey("questionTextSeed")) {
                    payload.put("questionText", GraphStep.renderText(script.scenarioId(),
                            String.valueOf(payload.remove("questionTextSeed")),
                            script.paraphraseIndex(), Map.of()));
                }
                if (payload.containsKey("contentTextSeed")) {
                    Object content = payload.get("content");
                    Map<String, Object> contentMap = content instanceof Map<?, ?> map
                            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
                    contentMap.put("text", GraphStep.renderText(script.scenarioId(),
                            String.valueOf(payload.remove("contentTextSeed")),
                            script.paraphraseIndex(), Map.of()));
                    payload.put("content", contentMap);
                }
            }
            case "INVOKE_CAPABILITY" -> {
                Object arguments = payload.get("arguments");
                Map<String, Object> args = arguments instanceof Map<?, ?> map
                        ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
                args.replaceAll((key, value) ->
                        "step:tip".equals(value) && anchorRef != null ? anchorRef : value);
                if (anchorRef != null && args.values().stream().noneMatch(
                        value -> value instanceof String text && text.startsWith("node:"))) {
                    args.putIfAbsent("nodeRef", anchorRef);
                }
                payload.put("arguments", args);
            }
            case "CONNECT_NODE" -> {
                payload.replaceAll((key, value) ->
                        "step:tip".equals(value) && anchorRef != null ? anchorRef : value);
            }
            default -> {
            }
        }
        return payload;
    }

    private List<String> anchorRefsFor(String family, AgentRequestEnvelope request) {
        if (!"CREATE_NODE".equals(family) && !"REQUEST_USER_INPUT".equals(family)) {
            return List.of();
        }
        UUID anchor = request.snapshot().anchorNodeId();
        return anchor == null ? List.of() : List.of("node:" + anchor);
    }
}
